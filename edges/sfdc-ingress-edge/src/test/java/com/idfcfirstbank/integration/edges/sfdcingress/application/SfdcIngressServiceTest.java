package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock.MockS3BlobStoreAdapter;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.support.InMemoryIdempotencyStore;
import com.idfcfirstbank.integration.edges.sfdcingress.support.MutableOrgConfig;
import com.idfcfirstbank.integration.edges.sfdcingress.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Behavioural coverage of the orchestration + C2/C3/C5 error handling (no Docker). */
class SfdcIngressServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
    private IdempotencyStorePort store;
    private RecordingPublisher publisher;
    private MutableOrgConfig orgConfig;
    private MockS3BlobStoreAdapter blob;
    private final AtomicInteger txn = new AtomicInteger();

    private SfdcIngressService service(EdgePolicies policies) {
        DedupeService dedupe = new DedupeService(store, clock);
        Normalizer normalizer = new Normalizer(() -> "txn-" + txn.incrementAndGet());
        return new SfdcIngressService(dedupe, store, orgConfig, blob, publisher, normalizer, policies, clock);
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore(clock);
        publisher = new RecordingPublisher();
        blob = new MockS3BlobStoreAdapter();
        orgConfig = new MutableOrgConfig()
                .route("PERSONAL_LOAN", "orig.sfdc.pl.v1")
                .knownOrg("ORG1");
    }

    @Test
    void newWinner_normalizesAndPublishesOnce() {
        SfdcIngressService service = service(EdgePolicies.defaults());
        EdgeResult result = service.ingest(event("n1", "c1", "PERSONAL_LOAN", "ORG1"));

        assertThat(result.disposition()).isEqualTo(EdgeDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.publishedTopics.get(0)).isEqualTo("orig.sfdc.pl.v1");
        assertThat(publisher.published.get(0).payloadRef()).startsWith("s3://"); // claim-check used
    }

    @Test
    void resendWhileInFlight_doesNotPublishAgain() {
        SfdcIngressService service = service(EdgePolicies.defaults());
        service.ingest(event("n1", "c1", "PERSONAL_LOAN", "ORG1"));
        EdgeResult resend = service.ingest(event("n1", "c2", "PERSONAL_LOAN", "ORG1"));

        assertThat(resend.disposition()).isEqualTo(EdgeDisposition.ACK_DUPLICATE_INFLIGHT);
        assertThat(publisher.published).as("idempotent: still exactly one publish").hasSize(1);
    }

    @Test
    void unknownType_refreshesAndRechecks_thenDlqAcksAsPermanent() {
        SfdcIngressService service = service(EdgePolicies.defaults());
        EdgeResult result = service.ingest(event("n1", "c1", "UNMAPPED_LINE", "ORG1"));

        assertThat(orgConfig.refreshCount).as("C2: refresh attempted before classifying").isEqualTo(1);
        assertThat(result.disposition()).isEqualTo(EdgeDisposition.ACK_DLQ_PERMANENT);
        assertThat(result.acknowledges()).isTrue();
        assertThat(publisher.dlqReasons).hasSize(1);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void unknownType_thatAppearsAfterRefresh_proceeds() {
        orgConfig.stageRouteForRefresh("LATE_LINE", "orig.sfdc.late.v1");
        SfdcIngressService service = service(EdgePolicies.defaults());

        EdgeResult result = service.ingest(event("n1", "c1", "LATE_LINE", "ORG1"));

        assertThat(orgConfig.refreshCount).isEqualTo(1);
        assertThat(result.disposition()).isEqualTo(EdgeDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void unknownOrg_stillAbsentAfterRefresh_isPermanentDlq() {
        SfdcIngressService service = service(EdgePolicies.defaults());
        EdgeResult result = service.ingest(event("n1", "c1", "PERSONAL_LOAN", "GHOST_ORG"));

        assertThat(orgConfig.refreshCount).isEqualTo(1);
        assertThat(result.disposition()).isEqualTo(EdgeDisposition.ACK_DLQ_PERMANENT);
    }

    @Test
    void transientPublishFailure_doesNotAck_untilPoisonThreshold() {
        SfdcIngressService service = service(new EdgePolicies(3, 1)); // poison at 3 redeliveries
        publisher.failPublishesWith(true);

        EdgeResult first = service.ingest(event("n1", "c1", "PERSONAL_LOAN", "ORG1"));
        EdgeResult second = service.ingest(event("n1", "c2", "PERSONAL_LOAN", "ORG1"));
        EdgeResult third = service.ingest(event("n1", "c3", "PERSONAL_LOAN", "ORG1"));

        assertThat(first.disposition()).isEqualTo(EdgeDisposition.RETRY_TRANSIENT);
        assertThat(first.acknowledges()).as("C2: transient is NOT acked").isFalse();
        assertThat(second.disposition()).isEqualTo(EdgeDisposition.RETRY_TRANSIENT);
        assertThat(third.disposition()).as("C5: poison breaker trips at N").isEqualTo(EdgeDisposition.ACK_DLQ_POISON);
        assertThat(third.acknowledges()).isTrue();
        assertThat(publisher.dlqReasons).hasSize(1);
        assertThat(publisher.dlqReasons.get(0)).contains("C5 poison");
    }

    @Test
    void transientThenRecovery_publishesOnRedelivery() {
        SfdcIngressService service = service(EdgePolicies.defaults());
        publisher.failPublishesWith(true);
        EdgeResult down = service.ingest(event("n1", "c1", "PERSONAL_LOAN", "ORG1"));
        assertThat(down.disposition()).isEqualTo(EdgeDisposition.RETRY_TRANSIENT);

        publisher.failPublishesWith(false); // broker recovers; SFDC redelivers
        EdgeResult up = service.ingest(event("n1", "c2", "PERSONAL_LOAN", "ORG1"));

        assertThat(up.disposition()).isEqualTo(EdgeDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(1);
    }

    private SfdcInboundEvent event(String notificationId, String correlationId, String type, String orgId) {
        return new SfdcInboundEvent(notificationId, correlationId, "rec-" + notificationId, "app-" + notificationId,
                orgId, type, "{\"k\":1}".getBytes(StandardCharsets.UTF_8), "application/json", clock.instant());
    }
}

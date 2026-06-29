package com.idfcfirstbank.integration.digitaledge.application;

import com.idfcfirstbank.integration.digitaledge.support.InMemoryIdempotencyGate;
import com.idfcfirstbank.integration.digitaledge.support.RecordingEnvelopePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Partner-resend dedupe (composite key) — the same guarantee the SFDC edge gives,
 * proving a partner's at-least-once retries never double-publish to the engine.
 */
class DigitalDedupeTest {

    private InMemoryIdempotencyGate gate;
    private RecordingEnvelopePublisher publisher;
    private ApplicationStatusStore statusStore;
    private DigitalIngressService service;

    @BeforeEach
    void setUp() {
        gate = new InMemoryIdempotencyGate();
        publisher = new RecordingEnvelopePublisher();
        statusStore = new ApplicationStatusStore();
        DigitalNormalizer normalizer = new DigitalNormalizer(
                () -> "txn-1", Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
        OriginationRouting routing = type ->
                "PERSONAL_LOAN".equals(type) ? Optional.of("orig.sfdc.pl.v1") : Optional.empty();
        service = new DigitalIngressService(gate, publisher, routing, normalizer, statusStore);
    }

    private DigitalOriginationCommand cmd(String requestId, String applicationRef, String type) {
        return new DigitalOriginationCommand("CRED", requestId, applicationRef, type, "ORG1",
                "corr-" + requestId, Map.of("pan", "ABCDE1234F"));
    }

    @Test
    void firstRequestPublishesOnce() {
        DigitalIngressResult result = service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        assertThat(result.disposition()).isEqualTo(DigitalDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.topics).containsExactly("orig.sfdc.pl.v1");
    }

    @Test
    void exactResendSameRequestIdDoesNotPublishAgain() {
        service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        DigitalIngressResult resend = service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        assertThat(resend.disposition()).isEqualTo(DigitalDisposition.ACK_DUPLICATE_REQUEST);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void newRequestIdSameApplicationDoesNotDoubleBook() {
        service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        DigitalIngressResult resend = service.ingest(cmd("req-2", "APP-1", "PERSONAL_LOAN"));
        assertThat(resend.disposition()).isEqualTo(DigitalDisposition.ACK_DUPLICATE_APPLICATION);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void differentApplicationsAreIndependentWinners() {
        service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        DigitalIngressResult other = service.ingest(cmd("req-3", "APP-2", "PERSONAL_LOAN"));
        assertThat(other.disposition()).isEqualTo(DigitalDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(2);
    }

    @Test
    void unknownTypeIsUnroutable() {
        DigitalIngressResult result = service.ingest(cmd("req-9", "APP-9", "MORTGAGE"));
        assertThat(result.disposition()).isEqualTo(DigitalDisposition.UNROUTABLE);
        assertThat(publisher.published).isEmpty();
    }

    @Test
    void applicationIdIsDeterministicAcrossResends() {
        String first = service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN")).applicationId();
        String resend = service.ingest(cmd("req-2", "APP-1", "PERSONAL_LOAN")).applicationId();
        assertThat(resend).isEqualTo(first).isEqualTo("DIG-CRED-APP-1");
    }
}

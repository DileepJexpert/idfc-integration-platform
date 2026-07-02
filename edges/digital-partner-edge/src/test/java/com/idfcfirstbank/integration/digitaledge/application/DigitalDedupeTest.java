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

    @Test
    void publishFailureReleasesClaims_soThePartnersRetryPublishes() {
        // THE crashed-window regression: publish fails -> 503 -> the partner
        // retries the SAME requestId. The old one-shot gates burned the id on the
        // first attempt, so every retry got a false duplicate-ACK and the
        // application was permanently lost while nothing was ever on Kafka.
        publisher.failPublishesWith(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN")))
                .isInstanceOf(RuntimeException.class);
        assertThat(publisher.published).isEmpty();

        publisher.failPublishesWith(false); // broker recovers; partner retries
        DigitalIngressResult retry = service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));

        assertThat(retry.disposition()).isEqualTo(DigitalDisposition.ACK_PROCESSED);
        assertThat(publisher.published).as("the retry re-drives the publish").hasSize(1);
    }

    @Test
    void unroutableRequestDoesNotBurnItsRequestId() {
        // Routing is checked BEFORE any claim: the partner fixes the type and
        // resends the SAME requestId, which must then proceed.
        DigitalIngressResult unroutable = service.ingest(cmd("req-1", "APP-1", "MORTGAGE"));
        assertThat(unroutable.disposition()).isEqualTo(DigitalDisposition.UNROUTABLE);

        DigitalIngressResult fixed = service.ingest(cmd("req-1", "APP-1", "PERSONAL_LOAN"));
        assertThat(fixed.disposition()).isEqualTo(DigitalDisposition.ACK_PROCESSED);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void staleUnpublishedOwnerIsSeizedByANewRequestIdForTheSameApplication() {
        // A crashed attempt claimed both gates but never published and never
        // released (pod death — no 503 path). A NEW requestId for the SAME
        // application arriving past the lease must seize ownership and publish,
        // instead of being duplicate-ACKed against a dead owner forever.
        java.util.concurrent.atomic.AtomicLong now =
                new java.util.concurrent.atomic.AtomicLong(Instant.parse("2026-06-29T00:00:00Z").toEpochMilli());
        Clock movable = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
            @Override public long millis() { return now.get(); }
        };
        gate = new InMemoryIdempotencyGate(movable, java.time.Duration.ofSeconds(60));
        DigitalNormalizer normalizer = new DigitalNormalizer(() -> "txn-1", movable);
        OriginationRouting routing = type ->
                "PERSONAL_LOAN".equals(type) ? Optional.of("orig.sfdc.pl.v1") : Optional.empty();
        service = new DigitalIngressService(gate, publisher, routing, normalizer, statusStore);

        // Crashed attempt: claims made, publish never confirmed, no release.
        gate.claimNotification("req-dead");
        gate.claimApplication("CRED::APP-1", "req-dead");

        now.addAndGet(120_000); // 2 minutes later — past the 60s lease
        DigitalIngressResult takeover = service.ingest(cmd("req-new", "APP-1", "PERSONAL_LOAN"));

        assertThat(takeover.disposition()).isEqualTo(DigitalDisposition.ACK_PROCESSED);
        assertThat(publisher.published).as("the stale owner's application is re-driven, not lost").hasSize(1);
    }
}

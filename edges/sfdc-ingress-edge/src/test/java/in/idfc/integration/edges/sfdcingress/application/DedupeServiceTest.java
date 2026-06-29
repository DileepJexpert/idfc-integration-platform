package in.idfc.integration.edges.sfdcingress.application;

import in.idfc.integration.edges.sfdcingress.domain.model.Decision;
import in.idfc.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import in.idfc.integration.edges.sfdcingress.domain.model.RecordStatus;
import in.idfc.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import in.idfc.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import in.idfc.integration.edges.sfdcingress.support.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit coverage of the 4 dedupe paths + composite-key resolution (no Docker). */
class DedupeServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
    private IdempotencyStorePort store;
    private DedupeService dedupe;

    @BeforeEach
    void setUp() {
        store = new InMemoryIdempotencyStore(clock);
        dedupe = new DedupeService(store, clock);
    }

    @Test
    void firstArrival_isNewWinner() {
        DedupeResult result = dedupe.resolve(event("n1", "c1", "rec1", "app1"));
        assertThat(result.path()).isEqualTo(DedupePath.NEW);
        assertThat(result.resend()).isFalse();
    }

    @Test
    void secondIdenticalArrival_isInFlightResend() {
        dedupe.resolve(event("n1", "c1", "rec1", "app1"));
        DedupeResult resend = dedupe.resolve(event("n1", "c2", "rec1", "app1"));
        assertThat(resend.path()).isEqualTo(DedupePath.IN_FLIGHT);
        assertThat(resend.resend()).isTrue();
        assertThat(resend.originalCorrelationId()).isEqualTo("c1"); // resendOf points at the original
    }

    @Test
    void arrivalAfterDecision_isDecidedResend() {
        dedupe.resolve(event("n1", "c1", "rec1", "app1"));
        IdempotencyRecord rec = store.findByNotificationId("n1").orElseThrow();
        store.compareAndSetStatus(rec, RecordStatus.DECIDED, new Decision("APPROVED", "A1", "{}"));

        DedupeResult resend = dedupe.resolve(event("n1", "c2", "rec1", "app1"));
        assertThat(resend.path()).isEqualTo(DedupePath.DECIDED);
    }

    @Test
    void arrivalAfterFailure_isFailedResend() {
        dedupe.resolve(event("n1", "c1", "rec1", "app1"));
        IdempotencyRecord rec = store.findByNotificationId("n1").orElseThrow();
        store.compareAndSetStatus(rec, RecordStatus.FAILED, null);

        DedupeResult resend = dedupe.resolve(event("n1", "c2", "rec1", "app1"));
        assertThat(resend.path()).isEqualTo(DedupePath.FAILED);
    }

    @Test
    void resendWithNewNotificationId_sameApplication_doesNotDoubleBook() {
        // First event books the application under n1.
        DedupeResult first = dedupe.resolve(event("n1", "c1", "rec1", "app1"));
        assertThat(first.path()).isEqualTo(DedupePath.NEW);

        // A user resend after a perceived failure: NEW notificationId, SAME application.
        DedupeResult resend = dedupe.resolve(event("n2-new-id", "c2", "rec1", "app1"));
        assertThat(resend.path()).as("must not start a second journey").isNotEqualTo(DedupePath.NEW);
        assertThat(resend.nonOwnerDuplicate()).isTrue();
        assertThat(resend.resend()).isTrue();
    }

    @Test
    void differentApplications_areIndependentWinners() {
        assertThat(dedupe.resolve(event("n1", "c1", "rec1", "app1")).path()).isEqualTo(DedupePath.NEW);
        assertThat(dedupe.resolve(event("n2", "c2", "rec2", "app2")).path()).isEqualTo(DedupePath.NEW);
    }

    @Test
    void correlationIdIsNeverADedupInput() {
        dedupe.resolve(event("n1", "corr-A", "rec1", "app1"));
        // Same identity, wildly different correlationId -> still a resend, not a new winner.
        DedupeResult resend = dedupe.resolve(event("n1", "corr-Z-different", "rec1", "app1"));
        assertThat(resend.path()).isEqualTo(DedupePath.IN_FLIGHT);
    }

    private SfdcInboundEvent event(String notificationId, String correlationId, String sfdcRecordId, String appRef) {
        return new SfdcInboundEvent(notificationId, correlationId, sfdcRecordId, appRef, "ORG1",
                "PERSONAL_LOAN", "{}".getBytes(StandardCharsets.UTF_8), "application/json", clock.instant());
    }
}

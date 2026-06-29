package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The idempotency aggregate (punch list §D). Immutable; transitions produce a
 * new instance via the {@code with*} helpers and are persisted through
 * {@code IdempotencyStorePort} as CAS operations (C4).
 *
 * <p>{@code version} mirrors the store's record generation — it is the
 * optimistic-lock token for compare-and-set, NOT a business field. A value of
 * {@code 0} means "not yet persisted".
 *
 * <p>This type has NO framework imports by design (hexagonal core).
 */
public record IdempotencyRecord(
        String notificationId,
        String sfdcRecordId,
        String applicationRef,
        RecordStatus status,
        Decision decision,
        String originalCorrelationId,
        String orgId,
        Instant receivedAt,
        Instant updatedAt,
        int retryCount,
        int redeliveryCount,
        long version) {

    public IdempotencyRecord {
        Objects.requireNonNull(notificationId, "notificationId");
        Objects.requireNonNull(status, "status");
    }

    /** First-write factory for a brand-new event (status RECEIVED, version 0). */
    public static IdempotencyRecord newReceived(String notificationId, String sfdcRecordId,
                                                String applicationRef, String orgId,
                                                String originalCorrelationId, Instant now) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef,
                RecordStatus.RECEIVED, null, originalCorrelationId, orgId,
                now, now, 0, 0, 0L);
    }

    public IdempotencyRecord withStatus(RecordStatus next, Instant now) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef, next, decision,
                originalCorrelationId, orgId, receivedAt, now, retryCount, redeliveryCount, version);
    }

    public IdempotencyRecord withDecision(Decision newDecision, Instant now) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef,
                RecordStatus.DECIDED, newDecision, originalCorrelationId, orgId, receivedAt, now,
                retryCount, redeliveryCount, version);
    }

    public IdempotencyRecord withRetryCount(int newRetryCount, Instant now) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef, status, decision,
                originalCorrelationId, orgId, receivedAt, now, newRetryCount, redeliveryCount, version);
    }

    public IdempotencyRecord withRedeliveryCount(int newRedeliveryCount, Instant now) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef, status, decision,
                originalCorrelationId, orgId, receivedAt, now, retryCount, newRedeliveryCount, version);
    }

    /** Carries a store-assigned generation onto the record after a read/write. */
    public IdempotencyRecord withVersion(long newVersion) {
        return new IdempotencyRecord(notificationId, sfdcRecordId, applicationRef, status, decision,
                originalCorrelationId, orgId, receivedAt, updatedAt, retryCount, redeliveryCount, newVersion);
    }

    public boolean hasApplicationFallback() {
        return sfdcRecordId != null && !sfdcRecordId.isBlank()
                && applicationRef != null && !applicationRef.isBlank();
    }
}

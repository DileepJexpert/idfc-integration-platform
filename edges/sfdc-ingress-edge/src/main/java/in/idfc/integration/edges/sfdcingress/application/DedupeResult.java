package in.idfc.integration.edges.sfdcingress.application;

import in.idfc.integration.edges.sfdcingress.domain.model.IdempotencyRecord;

/**
 * Verdict from {@link DedupeService}. {@code resend} is true when this arrival is
 * NOT the first for its dedup key. {@code originalCorrelationId} is the trace id
 * stored on the winning record — logged as {@code resendOf=<original>} so a
 * duplicate-looking trace is explainable (the resend's own correlationId is the
 * trace of the current call).
 */
public record DedupeResult(
        DedupePath path,
        IdempotencyRecord record,
        boolean resend,
        boolean nonOwnerDuplicate,
        String originalCorrelationId) {

    static DedupeResult winner(IdempotencyRecord record) {
        return new DedupeResult(DedupePath.NEW, record, false, false, record.originalCorrelationId());
    }

    static DedupeResult resend(DedupePath path, IdempotencyRecord record, boolean nonOwner) {
        return new DedupeResult(path, record, true, nonOwner, record.originalCorrelationId());
    }
}

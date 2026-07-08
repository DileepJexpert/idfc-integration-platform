package com.idfcfirstbank.integration.shared.sync;

import java.time.Instant;

/**
 * A single audited sync-lane invocation record — the sync counterpart to a journey
 * run, but for an IN-THREAD call that never touches the engine. One is written per
 * call (success AND failure) so money movement (imps-disbursal) and reads
 * (lms-utilities) are auditable even though they create no journey run.
 *
 * <p><b>IDS ONLY — PII-safe by construction.</b> This record deliberately carries no
 * account numbers, names, or amounts: only ids (the business dedup key, the
 * correlation id, the downstream reference), timing, and the outcome class. It is the
 * exact same discipline as the journey ops view (only ids/timestamps/enums cross).
 *
 * @param invocationId   platform-generated unique id for this call
 * @param capabilityKey  which sync capability (imps-disbursal, lms-utilities)
 * @param operation      the operation (transfer, OFFER_CHECK, …)
 * @param source         the digital partner (INDMONEY, SAVEIN) — trace/authz only
 * @param idempotencyKey the caller-supplied business dedup id (idempotentId / reqId)
 * @param correlationId  the caller's correlation id
 * @param transactionId  downstream reference if one came back (e.g. IMPS transactionId)
 * @param outcome        SUCCESS / BUSINESS_FAILURE / TECHNICAL_ERROR
 * @param errorClass     for TECHNICAL_ERROR only — PERMANENT / TRANSIENT / AMBIGUOUS
 * @param errorCode      for TECHNICAL_ERROR only — the technical code
 * @param startedAt      when the call started
 * @param durationMs     wall-clock duration of the in-thread call
 * @param deduped        true when this call was an idempotent replay (no new side-effect)
 */
public record SyncInvocation(
        String invocationId,
        String capabilityKey,
        String operation,
        String source,
        String idempotencyKey,
        String correlationId,
        String transactionId,
        SyncOutcome outcome,
        String errorClass,
        String errorCode,
        Instant startedAt,
        long durationMs,
        boolean deduped) {

    /** A definitive outcome is cached by the capability's idempotency store (success or business no). */
    public boolean isDefinitive() {
        return outcome == SyncOutcome.SUCCESS || outcome == SyncOutcome.BUSINESS_FAILURE;
    }

    /** A copy marked as an idempotent replay — used by the store when a prior definitive record exists. */
    public SyncInvocation asDeduped() {
        return new SyncInvocation(invocationId, capabilityKey, operation, source, idempotencyKey,
                correlationId, transactionId, outcome, errorClass, errorCode, startedAt, durationMs, true);
    }
}

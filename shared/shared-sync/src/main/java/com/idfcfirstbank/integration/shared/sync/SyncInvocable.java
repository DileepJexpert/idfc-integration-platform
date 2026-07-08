package com.idfcfirstbank.integration.shared.sync;

import java.util.Map;

/**
 * A capability that can be invoked SYNCHRONOUSLY, in-thread — the sync-lane
 * counterpart to an engine-invoked async capability. It takes an opaque payload +
 * the request context and returns the mapped response on the same call. Each sync
 * capability (imps-disbursal, lms-utilities, …) implements this and is dispatched
 * by {@link SyncCapabilityInvoker} on its {@link #capabilityKey()} — NOT by
 * partner/source (source never forks the code path).
 */
public interface SyncInvocable {

    String capabilityKey();

    /**
     * Run the operation in-thread and return the mapped response. A business "no"
     * is a normal return value (a result body); a technical failure throws
     * {@link SyncTechnicalException}.
     */
    Map<String, Object> invoke(String operation, Map<String, Object> payload, SyncRequestContext context);

    // --- Audit hooks (SyncInvocation) ------------------------------------------------
    // Defaults keep every capability working; imps-disbursal / lms-utilities override
    // the few that carry capability-specific semantics. All are ids-only / PII-safe.

    /** The caller-supplied business dedup id in the request (idempotentId / reqId), or null. */
    default String idempotencyKeyOf(Map<String, Object> payload) {
        return null;
    }

    /**
     * Classify a NON-throwing response as a business success or a business "no".
     * Default is SUCCESS (a plain returned body is a yes); capabilities that carry
     * an explicit status (IMPS {@code status}, a no-offer LMS reply) override this.
     */
    default SyncOutcome businessOutcome(Map<String, Object> response) {
        return SyncOutcome.SUCCESS;
    }

    /** A downstream reference from the response worth auditing (e.g. IMPS transactionId), or null. */
    default String downstreamRefOf(Map<String, Object> response) {
        return null;
    }
}

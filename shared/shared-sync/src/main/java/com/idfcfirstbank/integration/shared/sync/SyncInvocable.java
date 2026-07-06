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
}

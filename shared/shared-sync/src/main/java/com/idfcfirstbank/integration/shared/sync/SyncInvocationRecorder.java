package com.idfcfirstbank.integration.shared.sync;

/**
 * OUT port: persist one audit record per sync invocation. The
 * {@link SyncCapabilityInvoker} calls this once per call (success and failure alike);
 * the host app supplies the durable adapter (an in-memory store in dev, the shared
 * Aerospike store in prod — a host-config swap, never a new external DB here).
 *
 * <p>Implementations MUST be non-throwing / best-effort: an audit-write failure must
 * never turn a successful money transfer into a failure, nor a business "no" into a
 * technical error. The recorder is observability, not part of the transaction.
 */
public interface SyncInvocationRecorder {

    void record(SyncInvocation invocation);

    /** No-op recorder — the default when a host wires the invoker without auditing. */
    SyncInvocationRecorder NOOP = invocation -> { };
}

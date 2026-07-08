package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncInvocationRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapts the shared-sync {@link SyncInvocationRecorder} port to the edge's
 * {@link SyncInvocationStore}. Best-effort by contract: an audit-write failure is
 * logged (ids only) and swallowed — it must never turn a completed money transfer
 * into a failure. Auditing is observability, not part of the transaction.
 */
@Component
public class SyncInvocationRecorderAdapter implements SyncInvocationRecorder {

    private static final Logger log = LoggerFactory.getLogger(SyncInvocationRecorderAdapter.class);

    private final SyncInvocationStore store;

    public SyncInvocationRecorderAdapter(SyncInvocationStore store) {
        this.store = store;
    }

    @Override
    public void record(SyncInvocation invocation) {
        try {
            store.record(invocation);
        } catch (RuntimeException e) {
            log.error("sync-audit.record-failed invocationId={} capability={} outcome={} (cause={})",
                    invocation.invocationId(), invocation.capabilityKey(), invocation.outcome(), e.toString());
        }
    }
}

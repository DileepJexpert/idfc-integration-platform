package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for sync-lane audit records. The in-memory adapter is the dev
 * default; production swaps in the shared Aerospike store (a host-config change,
 * NOT a new external DB) with the same TTL discipline the journey run store uses.
 *
 * <p>The store also owns idempotency-replay detection: a second definitive record
 * for the same {@code (capabilityKey, idempotencyKey)} is stored as a
 * {@link SyncInvocation#deduped()} row, so audit shows the transfer was requested
 * twice but executed once.
 */
public interface SyncInvocationStore {

    /** Write one record. Marks the record {@code deduped} if a prior definitive one exists for its key. */
    void record(SyncInvocation invocation);

    Optional<SyncInvocation> find(String invocationId);

    /** All records that carry this business dedup id (the transfer + any deduped replays). */
    List<SyncInvocation> findByIdempotencyKey(String idempotencyKey);

    /** Every visible record; filtering/pagination happen server-side in the query service. */
    List<SyncInvocation> scanAll();
}

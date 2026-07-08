package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Dev/test sync-audit store: an in-memory map keyed by {@code invocationId}, with a
 * secondary index on {@code (capabilityKey|idempotencyKey)} for replay detection and
 * a bounded FIFO cap so a long-running dev process can't grow unbounded. The
 * PRODUCTION impl is the shared Aerospike store (a host-config swap) — the same store
 * the run/idempotency data uses, with a TTL that ages records out.
 *
 * <p>Non-durable: a restart forgets prior records (acceptable only in dev, exactly as
 * the in-memory idempotency store documents).
 */
@Component
public class InMemorySyncInvocationStore implements SyncInvocationStore {

    /** Soft cap for the dev store; prod uses Aerospike TTL instead of a count. */
    static final int MAX_RECORDS = 50_000;

    private final Map<String, SyncInvocation> byId = new ConcurrentHashMap<>();
    private final Map<String, String> definitiveByKey = new ConcurrentHashMap<>();
    private final Deque<String> insertionOrder = new ConcurrentLinkedDeque<>();

    @Override
    public void record(SyncInvocation invocation) {
        SyncInvocation toStore = invocation;
        if (invocation.idempotencyKey() != null && invocation.isDefinitive()) {
            String key = invocation.capabilityKey() + "|" + invocation.idempotencyKey();
            String prior = definitiveByKey.putIfAbsent(key, invocation.invocationId());
            if (prior != null) {
                // A definitive record for this business dedup id already exists — this
                // call was an idempotent replay, not a second transfer.
                toStore = invocation.asDeduped();
            }
        }
        byId.put(toStore.invocationId(), toStore);
        insertionOrder.addLast(toStore.invocationId());
        evictIfOverCap();
    }

    private void evictIfOverCap() {
        while (byId.size() > MAX_RECORDS) {
            String oldest = insertionOrder.pollFirst();
            if (oldest == null) {
                break;
            }
            byId.remove(oldest);
        }
    }

    @Override
    public Optional<SyncInvocation> find(String invocationId) {
        return Optional.ofNullable(invocationId == null ? null : byId.get(invocationId));
    }

    @Override
    public List<SyncInvocation> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return List.of();
        }
        return byId.values().stream()
                .filter(i -> idempotencyKey.equals(i.idempotencyKey()))
                .toList();
    }

    @Override
    public List<SyncInvocation> scanAll() {
        return new ArrayList<>(byId.values());
    }
}

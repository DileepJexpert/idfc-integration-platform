package com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.out.idempotency;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsIdempotencyStorePort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test idempotency store: an in-memory map keyed by {@code idempotentId}. The
 * PRODUCTION impl is the shared Aerospike store (a host-config swap) — same store
 * the async lane uses to dedupe partner resends, with a TTL exceeding the
 * partner's retry window and a cross-JVM put-if-absent reservation. Nothing here
 * is durable; a restart forgets prior transfers (acceptable only in dev).
 */
@Component
public class InMemoryImpsIdempotencyStore implements ImpsIdempotencyStorePort {

    private final Map<String, ImpsFtResult> store = new ConcurrentHashMap<>();

    @Override
    public Optional<ImpsFtResult> find(String idempotentId) {
        return Optional.ofNullable(store.get(idempotentId));
    }

    @Override
    public void save(String idempotentId, ImpsFtResult result) {
        store.put(idempotentId, result);
    }
}

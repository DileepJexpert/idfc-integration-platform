package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory {@link CapabilityIdempotencyStore} (default; single-instance demo).
 * {@code computeIfAbsent} guarantees the compute runs at most once per key even
 * under concurrent callers — the exactly-once primitive. A durable Aerospike
 * CREATE_ONLY variant swaps in behind the same port for multi-instance.
 */
public class InMemoryCapabilityIdempotencyStore implements CapabilityIdempotencyStore {

    private final ConcurrentHashMap<String, CapabilityResponse> cache = new ConcurrentHashMap<>();

    @Override
    public CapabilityResponse executeOnce(String key, Supplier<CapabilityResponse> compute) {
        return cache.computeIfAbsent(key, k -> compute.get());
    }
}

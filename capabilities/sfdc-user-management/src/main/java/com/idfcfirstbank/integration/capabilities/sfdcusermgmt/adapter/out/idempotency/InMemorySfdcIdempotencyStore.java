package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcIdempotencyStorePort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test in-memory SFDC write idempotency store. Production swaps in the shared
 * Aerospike store (a host-config swap) behind the same port — exactly like imps. Stores a
 * TOP-LEVEL defensive copy (HashMap, not {@code Map.copyOf}, because an SFDC JSON body may
 * carry null values). Nested maps/lists remain shared by reference — safe here because a
 * cached response is only serialised back to the caller, never mutated, and the passthrough
 * mappers don't mutate; a future mutating mapper would need a deep copy.
 */
@Component
public class InMemorySfdcIdempotencyStore implements SfdcIdempotencyStorePort {

    private final Map<String, Map<String, Object>> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<Map<String, Object>> find(String key) {
        Map<String, Object> hit = byKey.get(key);
        return hit == null ? Optional.empty() : Optional.of(new HashMap<>(hit));
    }

    @Override
    public void save(String key, Map<String, Object> response) {
        byKey.put(key, response == null ? new HashMap<>() : new HashMap<>(response));
    }
}

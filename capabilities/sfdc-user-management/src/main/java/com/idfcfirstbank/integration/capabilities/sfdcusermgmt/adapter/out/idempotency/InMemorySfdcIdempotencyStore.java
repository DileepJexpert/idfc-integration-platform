package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcIdempotencyStorePort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/test in-memory SFDC write idempotency store. Production swaps in the shared
 * Aerospike store (a host-config swap) behind the same port — exactly like imps. Stores
 * a defensive copy so a later mutation of the returned map can't corrupt a cached result.
 * HashMap copies (not {@code Map.copyOf}) because an SFDC JSON body may carry null values.
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

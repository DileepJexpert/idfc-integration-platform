package com.idfcfirstbank.integration.capabilities.verification.application;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * svcName -> {@link MapperPair} (config-as-data, mirrors the real {@code service.mappers}).
 * An unregistered svcName (or an NA/null mapper) falls back to raw-JSON passthrough —
 * the wrapper behaviour we preserve. Step-2+ registers the Karza/IMPS mapper pairs.
 */
@Component
public class MapperRegistry {

    private final Map<String, MapperPair> bySvcName = new ConcurrentHashMap<>();

    /** Register a svcName's mapper pair (called by each adapter slice's config). */
    public void register(String svcName, MapperPair pair) {
        bySvcName.put(svcName, pair);
    }

    /** The pair for a svcName; NA/unregistered -> passthrough (raw JSON). */
    public MapperPair forSvcName(String svcName) {
        return bySvcName.getOrDefault(svcName, MapperPair.passthrough());
    }
}

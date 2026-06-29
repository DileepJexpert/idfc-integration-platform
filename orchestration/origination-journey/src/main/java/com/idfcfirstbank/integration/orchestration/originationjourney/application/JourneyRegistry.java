package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the loaded journey definitions and resolves which one an inbound
 * origination event should start. Routing is by businessLine ({@code type}); for
 * the demo a single journey is loaded and used as the default, but the seam is
 * config-as-data so adding a (type -> journey) row is not a code change.
 */
public class JourneyRegistry {

    private final Map<String, JourneyDefinition> byKey = new LinkedHashMap<>();
    private final Map<String, String> typeToKey = new LinkedHashMap<>();
    private final String defaultKey;

    public JourneyRegistry(List<JourneyDefinition> definitions, Map<String, String> typeToKey) {
        for (JourneyDefinition d : definitions) {
            byKey.put(d.key(), d);
        }
        if (typeToKey != null) {
            this.typeToKey.putAll(typeToKey);
        }
        this.defaultKey = definitions.isEmpty() ? null : definitions.get(0).key();
    }

    public JourneyDefinition byKey(String key) {
        JourneyDefinition d = byKey.get(key);
        if (d == null) {
            throw new IllegalArgumentException("no journey definition for key '" + key + "'");
        }
        return d;
    }

    /** Resolve the journey for a businessLine type, falling back to the default. */
    public JourneyDefinition resolveForType(String type) {
        String key = typeToKey.getOrDefault(type, defaultKey);
        if (key == null) {
            throw new IllegalStateException("no journeys loaded; cannot resolve type '" + type + "'");
        }
        return byKey(key);
    }
}

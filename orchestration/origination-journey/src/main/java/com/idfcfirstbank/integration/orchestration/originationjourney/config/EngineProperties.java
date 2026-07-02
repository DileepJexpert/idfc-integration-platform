package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Engine configuration (config-as-data). Journeys, the decision topic, the
 * businessLine({@code type}) -> journey routing, the journey SOURCE (registry vs
 * classpath fallback), and the journey-state store are all data, not code.
 */
@ConfigurationProperties(prefix = "idfc.engine")
public record EngineProperties(
        String journeySource,            // "registry" (production shape) | "classpath" (bootstrap fallback)
        List<String> journeyResources,   // classpath-source resource list
        Registry registry,               // registry-source connection
        String decisionTopic,
        Map<String, String> typeToJourney,
        String stateStore,
        Aerospike aerospike) {

    public EngineProperties {
        // Journey source: exactly one of "classpath" (Docker-free bootstrap
        // fallback, flagged at startup) or "registry" (the designer->engine seam).
        // An unknown value fails closed — Phase 3 discipline, never guess.
        if (journeySource == null || journeySource.isBlank()) {
            journeySource = "classpath";
        }
        if (!"classpath".equalsIgnoreCase(journeySource) && !"registry".equalsIgnoreCase(journeySource)) {
            throw new IllegalStateException("idfc.engine.journey-source must be 'classpath' or"
                    + " 'registry' (got '" + journeySource + "') — refusing to start on an unknown"
                    + " journey source");
        }
        if (journeyResources == null || journeyResources.isEmpty()) {
            journeyResources = List.of("journeys/loan-origination.journey.json");
        }
        registry = registry == null ? new Registry(null, null, 0, 0, 0) : registry;
        if ("registry".equalsIgnoreCase(journeySource)) {
            // FAIL CLOSED: registry mode without an address/token cannot work and
            // must not quietly fall back to the classpath JAR (a silent dual
            // source of truth is how config drift becomes an incident).
            if (registry.baseUrl() == null || registry.baseUrl().isBlank()) {
                throw new IllegalStateException("idfc.engine.journey-source=registry but"
                        + " idfc.engine.registry.base-url is not set (env JOURNEY_REGISTRY_URL)");
            }
            if (registry.authToken() == null || registry.authToken().isBlank()) {
                throw new IllegalStateException("idfc.engine.journey-source=registry but"
                        + " idfc.engine.registry.auth-token is not set (env REGISTRY_AUTH_TOKEN) —"
                        + " there is no fail-open default");
            }
        }
        if (decisionTopic == null || decisionTopic.isBlank()) {
            decisionTopic = "orig.decision.v1";
        }
        if (typeToJourney == null) {
            typeToJourney = Map.of();
        }
        // Journey-instance store: "in-memory" (default, Docker-free) or "aerospike"
        // (durable, the org's only datastore — per ARCHITECTURE_origination-journey).
        if (stateStore == null || stateStore.isBlank()) {
            stateStore = "in-memory";
        }
        if (aerospike == null) {
            aerospike = new Aerospike(null, 0, null, null, 0);
        }
    }

    public boolean usesAerospikeState() {
        return "aerospike".equalsIgnoreCase(stateStore);
    }

    public boolean usesRegistrySource() {
        return "registry".equalsIgnoreCase(journeySource);
    }

    /** Journey-registry connection (the A1 service). Timeouts are REQUIRED — Karza lesson. */
    public record Registry(String baseUrl, String authToken, int refreshSeconds,
                           int connectTimeoutMs, int readTimeoutMs) {
        public Registry {
            refreshSeconds = refreshSeconds <= 0 ? 30 : refreshSeconds;
            connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
            readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        }
    }

    /** Aerospike connection + set for the journey-instance store. */
    public record Aerospike(String host, int port, String namespace, String instanceSet, int ttlSeconds) {
        public Aerospike {
            host = host == null ? "localhost" : host;
            port = port <= 0 ? 3000 : port;
            namespace = namespace == null ? "idfc" : namespace;
            instanceSet = instanceSet == null ? "journey_instance" : instanceSet;
            ttlSeconds = ttlSeconds <= 0 ? 7 * 24 * 60 * 60 : ttlSeconds; // 7 days default
        }
    }
}

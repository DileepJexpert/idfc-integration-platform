package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Engine configuration (config-as-data). Journeys, the decision topic, the
 * businessLine({@code type}) -> journey routing, and the journey-state store are
 * all data, not code.
 */
@ConfigurationProperties(prefix = "idfc.engine")
public record EngineProperties(
        List<String> journeyResources,
        String decisionTopic,
        Map<String, String> typeToJourney,
        String stateStore,
        Aerospike aerospike) {

    public EngineProperties {
        if (journeyResources == null || journeyResources.isEmpty()) {
            journeyResources = List.of("journeys/loan-origination.journey.json");
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

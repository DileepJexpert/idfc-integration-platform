package com.idfcfirstbank.integration.platform.journeyregistry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Registry configuration (config-as-data). Store selection, the service token,
 * and the CORS origins the DAG Designer (Flutter web) calls from are all data.
 */
@ConfigurationProperties(prefix = "idfc.registry")
public record RegistryProperties(
        String store,                              // "in-memory" (default, Docker-free) | "aerospike"
        String authToken,                          // X-Registry-Token — FAIL CLOSED, no default
        List<String> corsAllowedOriginPatterns,    // designer dev server origins
        Aerospike aerospike) {

    public RegistryProperties {
        if (store == null || store.isBlank()) {
            store = "in-memory";
        }
        // FAIL CLOSED (same rule as the edges, A0): the registry is the
        // maker-checker control plane — an unauthenticated registry would let any
        // caller publish what the engine runs. A missing secret refuses to start.
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalStateException(
                    "idfc.registry.auth-token is not set (env REGISTRY_AUTH_TOKEN) — the journey"
                            + " registry refuses to start without a service token; there is no"
                            + " fail-open default");
        }
        if (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty()) {
            corsAllowedOriginPatterns = List.of("http://localhost:[*]");
        }
        if (aerospike == null) {
            aerospike = new Aerospike(null, 0, null, null, null);
        }
    }

    public boolean usesAerospike() {
        return "aerospike".equalsIgnoreCase(store);
    }

    /** Aerospike connection + the two sets (meta pointers, version records). */
    public record Aerospike(String host, int port, String namespace, String metaSet, String versionSet) {
        public Aerospike {
            host = host == null ? "localhost" : host;
            port = port <= 0 ? 3000 : port;
            namespace = namespace == null ? "idfc" : namespace;
            metaSet = metaSet == null ? "journey_meta" : metaSet;
            versionSet = versionSet == null ? "journey_version" : versionSet;
        }
    }
}

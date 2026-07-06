package com.idfcfirstbank.integration.digitaledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config for the digital-lending SYNC lane the edge hosts (impsFT /
 * callLmsUtilities). {@code auth.accepted-tokens} is the fail-closed Ory/Hydra
 * Bearer allow-list — the dev introspection seam; production swaps in real Kong/
 * Hydra token introspection behind the same {@code BearerTokenValidator}. An EMPTY
 * allow-list accepts NOTHING (no blank-token bypass). The per-capability vendor
 * hosts + timeouts live under their own {@code imps-disbursal.*} /
 * {@code lms-utilities.*} keys (bound by the capability modules).
 */
@ConfigurationProperties(prefix = "idfc.sync-edge")
public record SyncEdgeProperties(Auth auth) {

    public SyncEdgeProperties {
        auth = auth == null ? new Auth(List.of()) : auth;
    }

    public record Auth(List<String> acceptedTokens) {
        public Auth {
            acceptedTokens = acceptedTokens == null ? List.of() : List.copyOf(acceptedTokens);
        }
    }
}

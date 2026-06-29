package com.idfcfirstbank.integration.capabilities.scoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Scoring wiring (config-as-data). {@code ficoMode} selects the mock or real FICO
 * adapter; {@code ficoUrl} is the FICO base URL (docker mock in compose, real in
 * prod); {@code threshold} is the bureau-score cutoff for the decision rule.
 */
@ConfigurationProperties(prefix = "idfc.scoring")
public record ScoringProperties(String ficoMode, String ficoUrl, Integer threshold) {

    public ScoringProperties {
        if (ficoMode == null || ficoMode.isBlank()) {
            ficoMode = "mock";
        }
        if (threshold == null) {
            threshold = 700;
        }
    }

    public boolean isReal() {
        return "real".equalsIgnoreCase(ficoMode);
    }
}

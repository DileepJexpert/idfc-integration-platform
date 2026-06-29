package com.idfcfirstbank.integration.capabilities.customer.party.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Posidex wiring (config-as-data). {@code mode} selects the mock or real adapter;
 * {@code url} is the Posidex base URL (docker mock in compose, real in prod).
 */
@ConfigurationProperties(prefix = "idfc.customer-party.posidex")
public record PosidexProperties(String mode, String url) {

    public PosidexProperties {
        if (mode == null || mode.isBlank()) {
            mode = "mock";
        }
    }

    public boolean isReal() {
        return "real".equalsIgnoreCase(mode);
    }
}

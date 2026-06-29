package com.idfcfirstbank.integration.capabilities.bureau.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CIBIL wiring (config-as-data). {@code mode} selects the mock or real adapter;
 * {@code url} is the CIBIL base URL (docker mock in compose, real in prod).
 */
@ConfigurationProperties(prefix = "idfc.bureau.cibil")
public record CibilProperties(String mode, String url) {

    public CibilProperties {
        if (mode == null || mode.isBlank()) {
            mode = "mock";
        }
    }

    public boolean isReal() {
        return "real".equalsIgnoreCase(mode);
    }
}

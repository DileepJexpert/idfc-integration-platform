package com.idfcfirstbank.integration.capabilities.lending.origination.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FinnOne wiring (config-as-data). {@code mode} selects the mock or real adapter.
 * The real adapter is an Oracle STORED PROCEDURE over JDBC (NOT HTTP) and needs a
 * {@code spring.datasource.*} to be configured.
 */
@ConfigurationProperties(prefix = "idfc.lending-origination.finnone")
public record FinnOneProperties(String mode) {

    public FinnOneProperties {
        if (mode == null || mode.isBlank()) {
            mode = "mock";
        }
    }

    public boolean isReal() {
        return "real".equalsIgnoreCase(mode);
    }
}

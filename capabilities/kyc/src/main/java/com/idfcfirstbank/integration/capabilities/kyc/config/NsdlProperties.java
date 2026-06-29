package com.idfcfirstbank.integration.capabilities.kyc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * NSDL wiring (config-as-data). {@code mode} selects the mock or real adapter;
 * {@code url} is the NSDL base URL (docker mock in compose, real in prod).
 */
@ConfigurationProperties(prefix = "idfc.kyc.nsdl")
public record NsdlProperties(String mode, String url) {

    public NsdlProperties {
        if (mode == null || mode.isBlank()) {
            mode = "mock";
        }
    }

    public boolean isReal() {
        return "real".equalsIgnoreCase(mode);
    }
}

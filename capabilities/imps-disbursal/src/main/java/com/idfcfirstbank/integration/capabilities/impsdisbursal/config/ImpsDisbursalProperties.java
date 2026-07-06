package com.idfcfirstbank.integration.capabilities.impsdisbursal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config for the imps-disbursal sync lane. The vendor host + auth are config (real
 * IMPS backend later = a host swap, no code change); the timeouts are MANDATORY
 * (the caller blocks). {@code auth.accepted-tokens} is the fail-closed Bearer
 * allow-list — the introspection seam for dev; production swaps in real Ory/Hydra
 * token introspection. An EMPTY allow-list authorizes nothing (no blank-token
 * bypass).
 */
@ConfigurationProperties("imps-disbursal")
public record ImpsDisbursalProperties(
        String vendorBaseUrl,
        String vendorAuthToken,
        int connectTimeoutMs,
        int readTimeoutMs,
        Auth auth) {

    public ImpsDisbursalProperties {
        vendorBaseUrl = blankToNull(vendorBaseUrl);
        vendorAuthToken = blankToNull(vendorAuthToken);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        auth = auth == null ? new Auth(List.of()) : auth;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /** Edge auth: the set of Bearer tokens accepted at the sync ingress (dev seam). */
    public record Auth(List<String> acceptedTokens) {
        public Auth {
            acceptedTokens = acceptedTokens == null ? List.of() : List.copyOf(acceptedTokens);
        }
    }
}

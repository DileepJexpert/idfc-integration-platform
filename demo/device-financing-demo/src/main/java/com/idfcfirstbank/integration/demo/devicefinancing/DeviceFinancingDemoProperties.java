package com.idfcfirstbank.integration.demo.devicefinancing;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * The demo's whole point, as a type: a BRAND IS A CONFIG ROW. The vendor is now
 * reached over REAL HTTP (only its response DATA is mocked, on the mock-vendors
 * server) — so a brand row carries the auth SCHEME (BASIC / OAUTH / NA, spelled
 * as the legacy estate spells them), whether the validation activity runs, and
 * WHERE the vendor's pass flag lives (the per-brand "pass-logic field path").
 * The vendor's base URL + timeouts + OAuth token URL are shared config.
 *
 * <p>Nothing here is a canned response: the pass/decline DATA comes back from
 * the real HTTP call. Adding a brand is still a config row.
 */
@ConfigurationProperties("demo.device-financing")
public record DeviceFinancingDemoProperties(
        String vendorBaseUrl,
        String tokenUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, BrandRow> brands) {

    public DeviceFinancingDemoProperties {
        vendorBaseUrl = blankToNull(vendorBaseUrl);
        tokenUrl = blankToNull(tokenUrl);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        brands = brands == null ? Map.of() : Map.copyOf(brands);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * One legacy brand config row: auth SCHEME (and its credentials/scope),
     * whether the validation activity runs, the dotted path to the vendor's
     * pass field in ITS response shape, and the value that means "pass".
     */
    public record BrandRow(
            String authType,
            boolean validationRequired,
            String passPath,
            String passValue,
            String basicUser,
            String basicPassword,
            String scope) {
    }
}

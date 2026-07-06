package com.idfcfirstbank.integration.capabilities.devicefinancing;

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
@ConfigurationProperties("device-financing")
public record DeviceFinancingProperties(
        String vendorBaseUrl,
        String tokenUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        Map<String, BrandRow> brands) {

    public DeviceFinancingProperties {
        vendorBaseUrl = blankToNull(vendorBaseUrl);
        tokenUrl = blankToNull(tokenUrl);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        brands = brands == null ? Map.of() : Map.copyOf(brands);
    }

    /**
     * Resolve the brand-row KEY for an SFDC svcName. The REAL door carries brand
     * implicitly in the svcName (no brand field in the payload); each brand row
     * that has a real SFDC front door declares its {@code svcName}. Carried as a
     * FIELD on the all-uppercase-keyed brands map — which binds cleanly — rather
     * than a separate mixed-case-keyed collection that Spring relaxed binding
     * fails to bind.
     */
    public String brandForSvcName(String svcName) {
        if (svcName == null) {
            return null;
        }
        for (Map.Entry<String, BrandRow> e : brands.entrySet()) {
            if (svcName.equals(e.getValue().svcName())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * One legacy brand config row: auth SCHEME (and its credentials/scope),
     * whether the validation activity runs, the dotted path to the vendor's
     * pass field in ITS response shape, the value that means "pass", and — for a
     * brand with a real SFDC front door — the {@code svcName} that maps to it.
     */
    public record BrandRow(
            String authType,
            boolean validationRequired,
            String passPath,
            String passValue,
            String basicUser,
            String basicPassword,
            String scope,
            String svcName) {
    }
}

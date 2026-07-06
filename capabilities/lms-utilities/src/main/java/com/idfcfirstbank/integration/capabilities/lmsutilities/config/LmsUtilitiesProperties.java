package com.idfcfirstbank.integration.capabilities.lmsutilities.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config for the lms-utilities sync lane. The vendor host + auth are config (real
 * LMS backend later = a host swap, no code change); the timeouts are MANDATORY (the
 * caller blocks). {@code known-request-codes} is the fail-closed allow-list of LMS
 * requestCodes the lane will dispatch — adding a code (OFFER_CHECK, …) is CONFIG, and
 * an unknown code is refused BEFORE any backend call, never silently run. An EMPTY
 * list dispatches nothing (no blank-code bypass).
 */
@ConfigurationProperties("lms-utilities")
public record LmsUtilitiesProperties(
        String vendorBaseUrl,
        String vendorAuthToken,
        int connectTimeoutMs,
        int readTimeoutMs,
        List<String> knownRequestCodes) {

    public LmsUtilitiesProperties {
        vendorBaseUrl = blankToNull(vendorBaseUrl);
        vendorAuthToken = blankToNull(vendorAuthToken);
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        knownRequestCodes = knownRequestCodes == null ? List.of() : List.copyOf(knownRequestCodes);
    }

    /** Whether {@code requestCode} is in the fail-closed allow-list (a blank code is never known). */
    public boolean isKnown(String requestCode) {
        return requestCode != null && !requestCode.isBlank() && knownRequestCodes.contains(requestCode);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

package com.idfcfirstbank.integration.capabilities.verification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Verification config-as-data: the CONTROL-PLANE routes (svcName -> endpoint/auth),
 * the allow-list of callable hosts (anti-SSRF), and the retry/DLQ policy. Adding a
 * svcName is a config row (+ its adapter/mapper), not code.
 */
@ConfigurationProperties(prefix = "idfc.verification")
public record VerificationProperties(
        List<Route> routes,
        List<String> allowedHosts,
        Retry retry,
        String dlqTopic) {

    public VerificationProperties {
        routes = routes == null ? List.of() : routes;
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        retry = retry == null ? new Retry(2, 200) : retry;
        dlqTopic = dlqTopic == null ? "cap.verification.dlq.v1" : dlqTopic;
    }

    /** A control-plane route row: svcName -> endpoint + auth style. */
    public record Route(String svcName, String baseUrl, String authType) {}

    /** Retry-then-DLQ policy (correction #2). */
    public record Retry(int maxAttempts, long backoffMillis) {
        public Retry {
            maxAttempts = maxAttempts <= 0 ? 2 : maxAttempts;
            backoffMillis = backoffMillis < 0 ? 200 : backoffMillis;
        }
    }
}

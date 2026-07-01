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
        String dlqTopic,
        String sfdcNotifyTopic) {

    public VerificationProperties {
        routes = routes == null ? List.of() : routes;
        allowedHosts = allowedHosts == null ? List.of() : allowedHosts;
        retry = retry == null ? new Retry(3, 200, 5000, true) : retry;
        dlqTopic = dlqTopic == null ? "cap.verification.dlq.v1" : dlqTopic;
        sfdcNotifyTopic = sfdcNotifyTopic == null ? "sfdc.response.notify.v1" : sfdcNotifyTopic;
    }

    /** A control-plane route row: svcName -> endpoint + auth style. */
    public record Route(String svcName, String baseUrl, String authType) {}

    /** Classified-retry policy (spec v2 §C): exponential backoff + jitter, then DLQ + notify. */
    public record Retry(int maxAttempts, long backoffMillis, long maxBackoffMillis, boolean jitter) {
        public Retry {
            maxAttempts = maxAttempts <= 0 ? 3 : maxAttempts;
            backoffMillis = backoffMillis < 0 ? 200 : backoffMillis;
            maxBackoffMillis = maxBackoffMillis <= 0 ? 5000 : maxBackoffMillis;
        }
    }
}

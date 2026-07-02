package com.idfcfirstbank.integration.platform.opsquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Ops read-API configuration. The token follows the A0 rule — FAIL CLOSED, no
 * compiled-in default — and is deliberately a DIFFERENT secret from the
 * registry's (D14): the designer's maker/checker token must never open the ops
 * window, nor the ops token the registry.
 */
@ConfigurationProperties(prefix = "idfc.ops")
public record OpsQueryProperties(
        String authToken,                          // X-Ops-Token — FAIL CLOSED, no default
        List<String> corsAllowedOriginPatterns) {  // the ops view is a browser app (Phase 1)

    public OpsQueryProperties {
        if (authToken == null || authToken.isBlank()) {
            throw new IllegalStateException(
                    "idfc.ops.auth-token is not set (env OPS_API_TOKEN) — the ops read-API"
                            + " refuses to start without its own service token; there is no"
                            + " fail-open default and the registry token does NOT authorize it");
        }
        if (corsAllowedOriginPatterns == null || corsAllowedOriginPatterns.isEmpty()) {
            corsAllowedOriginPatterns = List.of("http://localhost:[*]");
        }
    }
}

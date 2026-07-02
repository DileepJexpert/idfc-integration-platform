package com.idfcfirstbank.integration.digitaledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Digital edge configuration (config-as-data). Partners and routing are DATA —
 * onboarding a partner (CRED/FLIPKART/GROWW) is a config row with its token +
 * callback, NOT a new service. Routing maps the businessLine {@code type} to the
 * SAME origination topic the SFDC edge uses.
 */
@ConfigurationProperties(prefix = "idfc.digital-edge")
public record DigitalEdgeProperties(
        List<Partner> partners,
        List<RouteRule> routing,
        Aerospike aerospike,
        String decisionTopic,
        int publishLeaseSeconds) {   // unpublished claim older than this = crashed attempt (default 60)

    public DigitalEdgeProperties {
        partners = partners == null ? List.of() : partners;
        // FAIL CLOSED (Phase 5): a configured partner row with no token means the
        // env secret is missing — refuse to start rather than silently carrying a
        // partner nobody can authenticate as (or, worse, a compiled-in default
        // token anyone can read from the repo).
        for (Partner p : partners) {
            if (p.token() == null || p.token().isBlank()) {
                throw new IllegalStateException(
                        "partner '" + p.code() + "' has no auth token (env " + p.code() + "_TOKEN) — the"
                                + " digital edge refuses to start; there is no fail-open default");
            }
        }
        routing = routing == null ? List.of() : routing;
        aerospike = aerospike == null ? new Aerospike(null, 0, null, null, null, 0) : aerospike;
        decisionTopic = decisionTopic == null || decisionTopic.isBlank() ? "orig.decision.v1" : decisionTopic;
        publishLeaseSeconds = publishLeaseSeconds <= 0 ? 60 : publishLeaseSeconds;
    }

    /** A registered partner: auth token + decision callback URL (secrets via config/Vault). */
    public record Partner(String code, String token, String callbackUrl) {
    }

    /** (businessLine type -> origination topic) — the SAME topics the SFDC edge routes to. */
    public record RouteRule(String type, String topic) {
    }

    /** SAME platform store; sets default to the platform idempotency sets. */
    public record Aerospike(String host, int port, String namespace,
                            String notificationSet, String applicationSet, int ttlSeconds) {
        public Aerospike {
            host = host == null ? "localhost" : host;
            port = port <= 0 ? 3000 : port;
            namespace = namespace == null ? "idfc" : namespace;
            notificationSet = notificationSet == null ? "idem" : notificationSet;
            applicationSet = applicationSet == null ? "idem_app" : applicationSet;
            ttlSeconds = ttlSeconds <= 0 ? 30 * 24 * 60 * 60 : ttlSeconds;
        }
    }
}

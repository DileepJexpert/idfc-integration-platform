package com.idfcfirstbank.integration.edges.sfdcingress.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Edge configuration (org-config-as-data lives here for Slice 1; later it moves
 * to the config store behind {@code OrgConfigPort}). Routing and known orgs are
 * DATA — adding a business line or org is a config change, not code (§E).
 */
@ConfigurationProperties(prefix = "idfc.edge")
public record EdgeProperties(
        Auth auth,
        int poisonRedeliveryThreshold,   // C5 ceiling (default 5)
        int maxJourneyRetry,             // C3 ceiling (default 1)
        int publishLeaseSeconds,         // IN_FLIGHT older than this = crashed attempt, re-drive (default 60)
        String dlqTopic,
        Finnone finnone,                 // backpressure harness config (§G)
        List<RouteRule> routing,
        List<String> knownOrgs) {

    public EdgeProperties {
        // FAIL CLOSED (Phase 5): this edge is internet-facing — starting with no
        // auth token (or a silently-applied compiled-in default) would let any
        // caller push notifications. A missing secret refuses to start, loudly.
        if (auth == null || auth.expectedToken() == null || auth.expectedToken().isBlank()) {
            throw new IllegalStateException(
                    "idfc.edge.auth.expected-token is not set (env SFDC_EDGE_TOKEN) — the SFDC edge"
                            + " refuses to start without an auth token; there is no fail-open default");
        }
        poisonRedeliveryThreshold = poisonRedeliveryThreshold <= 0 ? 5 : poisonRedeliveryThreshold;
        maxJourneyRetry = maxJourneyRetry <= 0 ? 1 : maxJourneyRetry;
        publishLeaseSeconds = publishLeaseSeconds <= 0 ? 60 : publishLeaseSeconds;
        dlqTopic = dlqTopic == null ? "orig.sfdc.dlq.v1" : dlqTopic;
        finnone = finnone == null ? new Finnone(4, "orig.sfdc.pl.v1") : finnone;
        routing = routing == null ? List.of() : routing;
        knownOrgs = knownOrgs == null ? List.of() : knownOrgs;
    }

    /** Convenience accessor for the backpressure cap N. */
    public int finnoneMaxConcurrency() {
        return finnone.maxConcurrency();
    }

    /** Mock two-token auth config (real Hydra+Kong is a later slice; values via Vault). */
    public record Auth(String expectedToken) {
    }

    /** Backpressure harness (§G): cap N + the origination topics it consumes. */
    public record Finnone(int maxConcurrency, String consumeTopics) {
        public Finnone {
            maxConcurrency = maxConcurrency <= 0 ? 4 : maxConcurrency;
        }
    }

    /** A single (type -> topic + downstream journey) routing row (§E). */
    public record RouteRule(String type, String topic, String downstreamJourney) {
    }
}

package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Config-as-data for the sfdc-user-management sync capability. TWO tables compose:
 *
 * <ul>
 *   <li>{@code routes}: svcName -&gt; {@code path} (+ read/write flag). Mirrors the
 *       verification capability's control-plane route rows, but a route holds only the
 *       PATH — not a full baseUrl — because the host is chosen per-request by org.</li>
 *   <li>{@code orgs}: orgName -&gt; {@code baseUrl} (+ auth + enabled). The NEW element:
 *       the request's org name selects WHICH SFDC instance to call. This table is a
 *       CURATED ALLOW-LIST — the inbound message supplies only the org NAME (a key),
 *       never an endpoint (anti-SSRF, same discipline as the route resolver's host
 *       allow-list). An unknown org fails closed; there is no default org.</li>
 * </ul>
 *
 * <p>Final target = {@code orgs[org].baseUrl + routes[svcName].path}. Timeouts are
 * MANDATORY (the caller blocks). Real SFDC orgs later = host + token swaps here, no code.
 * Adding a svcName or an org is a config ROW.
 */
@ConfigurationProperties("sfdc-user-mgmt")
public record SfdcUserManagementProperties(
        int connectTimeoutMs,
        int readTimeoutMs,
        List<Route> routes,
        List<Org> orgs) {

    public SfdcUserManagementProperties {
        connectTimeoutMs = connectTimeoutMs <= 0 ? 3_000 : connectTimeoutMs;
        readTimeoutMs = readTimeoutMs <= 0 ? 10_000 : readTimeoutMs;
        routes = routes == null ? List.of() : List.copyOf(routes);
        orgs = orgs == null ? List.of() : List.copyOf(orgs);
    }

    /**
     * A control-plane route row: svcName -&gt; path, with a read/write flag. Reads run
     * on the sync lane with no idempotency; writes (slice 2) are idempotency-guarded.
     * {@code write} defaults to false (a row is a read unless it declares otherwise).
     */
    public record Route(String svcName, String path, boolean write) {}

    /**
     * A target SFDC org row: orgName -&gt; baseUrl + auth. {@code enabled} defaults to
     * true when omitted (a present row is live unless explicitly disabled); an UNKNOWN
     * org — one with no row — always fails closed (never a default org).
     */
    public record Org(String orgName, String baseUrl, String authType, String authToken, Boolean enabled) {
        public Org {
            enabled = enabled == null ? Boolean.TRUE : enabled;
        }
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}

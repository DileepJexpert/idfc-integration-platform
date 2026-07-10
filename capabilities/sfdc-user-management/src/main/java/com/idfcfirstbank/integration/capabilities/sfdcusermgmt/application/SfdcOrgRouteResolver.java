package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.SfdcAuthType;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The control-plane resolver for sfdc-user-management. It composes TWO config tables:
 *
 * <ul>
 *   <li><b>svcName -&gt; path</b> (the route table) — the operation, mirroring the
 *       verification capability's {@code ConfigRouteResolver}.</li>
 *   <li><b>orgName -&gt; baseUrl</b> (the org table) — WHICH SFDC instance to call.</li>
 * </ul>
 *
 * <p>final target url = {@code orgs[org].baseUrl + routes[svcName].path}.
 *
 * <h2>Scoped exception — org IS a routing key here (and ONLY here)</h2>
 * On the lending side of this platform, org/partner/brand are config ATTRIBUTES and are
 * never journey-routing keys (one SFDC, the org never forks the path). THIS capability is
 * the deliberate, scoped exception: its whole job is to fan a request out to one of
 * SEVERAL SFDC org instances, so here the org name selects the egress TARGET
 * (org-as-egress-target). Two contexts, two rules, both intentional — the lending
 * principle is NOT abandoned; a future reader should read this comment before "fixing" it.
 *
 * <h2>Fail closed / anti-SSRF</h2>
 * Endpoints come from OUR org table (a curated allow-list). The inbound message supplies
 * only the org NAME (a key into the table), never a URL — so there is no way for a caller
 * to point us at an arbitrary host. An unknown org, a disabled org, or an unknown svcName
 * all fail closed as PERMANENT; there is NO default org (the legacy fail-open orgId is not
 * reproduced). A route {@code path} that tries to smuggle a host (scheme or missing leading
 * "/") is rejected too, so a misconfigured row can't override the org's host.
 */
@Component
public class SfdcOrgRouteResolver {

    private final Map<String, SfdcUserManagementProperties.Route> routesBySvcName = new HashMap<>();
    private final Map<String, SfdcUserManagementProperties.Org> orgsByName = new HashMap<>();

    public SfdcOrgRouteResolver(SfdcUserManagementProperties properties) {
        for (SfdcUserManagementProperties.Route r : properties.routes()) {
            routesBySvcName.put(r.svcName(), r);
        }
        for (SfdcUserManagementProperties.Org o : properties.orgs()) {
            orgsByName.put(o.orgName(), o);
        }
    }

    /**
     * Resolve (svcName, orgName) to a concrete SFDC target, or fail closed.
     *
     * @throws SyncTechnicalException PERMANENT {@code NO_ROUTE} (unknown svcName),
     *         {@code UNKNOWN_ORG} (missing/unknown org), {@code ORG_DISABLED}
     *         (org present but toggled off), or {@code BAD_ROUTE_PATH} (a path that
     *         would override the org host).
     */
    public ResolvedSfdcTarget resolve(String svcName, String orgName) {
        SfdcUserManagementProperties.Route route = routesBySvcName.get(svcName);
        if (route == null) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "NO_ROUTE",
                    "no control-plane route registered for svcName=" + svcName);
        }
        if (orgName == null || orgName.isBlank()) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_ORG",
                    "orgName is required to select the target SFDC org");
        }
        SfdcUserManagementProperties.Org org = orgsByName.get(orgName);
        if (org == null) {
            // Fail closed: no default org, ever.
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_ORG",
                    "no SFDC org endpoint is allow-listed for orgName=" + orgName);
        }
        if (!org.isEnabled()) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "ORG_DISABLED",
                    "SFDC org is configured but disabled: orgName=" + orgName);
        }
        String path = route.path() == null ? "" : route.path();
        if (path.contains("://") || !path.startsWith("/")) {
            // A path must not smuggle a host — the org table is the sole source of the host.
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "BAD_ROUTE_PATH",
                    "route path for svcName=" + svcName + " must be a leading-slash path, not a URL");
        }
        String base = org.baseUrl() == null ? "" : org.baseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return new ResolvedSfdcTarget(svcName, orgName, base + path, authType(org.authType()),
                org.authToken(), route.write());
    }

    private static SfdcAuthType authType(String raw) {
        if (raw == null || raw.isBlank()) {
            return SfdcAuthType.NONE;   // explicit "no auth"
        }
        try {
            return SfdcAuthType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // FAIL CLOSED on an unknown enum (platform principle): a typo'd authType must
            // NOT silently drop auth to NONE. The misconfigured org's requests fail closed
            // with a clear code rather than ever calling SFDC unauthenticated. (Deliberately
            // stricter than the verification capability's lenient default-to-NONE.)
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "BAD_AUTH_CONFIG",
                    "unknown authType configured for org: " + raw);
        }
    }
}

package com.idfcfirstbank.integration.edges.sfdcingress.domain.port;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SourceSystem;

import java.util.Optional;

/**
 * OUT port for org-config-as-data (punch list §E). Routing and org membership
 * are DATA, not code — adding a business line is a config row. Supports the C2
 * unknown-org refresh-and-recheck: {@link #refresh()} reloads, then the caller
 * re-checks once before classifying.
 */
public interface OrgConfigPort {
    Optional<RoutingDecision> resolveRouting(SourceSystem source, String typeCode);

    boolean isKnownOrg(String orgId);

    /** Reload config from the backing store (config refresh for unknown-org recheck). */
    void refresh();
}

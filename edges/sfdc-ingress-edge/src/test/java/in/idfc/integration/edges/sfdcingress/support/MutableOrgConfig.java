package in.idfc.integration.edges.sfdcingress.support;

import in.idfc.integration.edges.sfdcingress.domain.model.RoutingDecision;
import in.idfc.integration.edges.sfdcingress.domain.model.SourceSystem;
import in.idfc.integration.edges.sfdcingress.domain.port.OrgConfigPort;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mutable org config for tests of the C2 refresh-and-recheck: staged routes/orgs
 * become visible only after {@link #refresh()} is called, modelling a stale
 * config that a refresh reloads.
 */
public class MutableOrgConfig implements OrgConfigPort {

    private final Map<String, RoutingDecision> live = new HashMap<>();
    private final Set<String> liveOrgs = new HashSet<>();
    private final Map<String, RoutingDecision> staged = new HashMap<>();
    private final Set<String> stagedOrgs = new HashSet<>();
    public int refreshCount;

    public MutableOrgConfig route(String type, String topic) {
        live.put(type, new RoutingDecision(SourceSystem.SFDC, type, topic, "origination-journey"));
        return this;
    }

    public MutableOrgConfig knownOrg(String orgId) {
        liveOrgs.add(orgId);
        return this;
    }

    /** Stage a route/org that only appears after the next refresh(). */
    public MutableOrgConfig stageRouteForRefresh(String type, String topic) {
        staged.put(type, new RoutingDecision(SourceSystem.SFDC, type, topic, "origination-journey"));
        return this;
    }

    public MutableOrgConfig stageOrgForRefresh(String orgId) {
        stagedOrgs.add(orgId);
        return this;
    }

    @Override
    public Optional<RoutingDecision> resolveRouting(SourceSystem source, String typeCode) {
        return Optional.ofNullable(live.get(typeCode));
    }

    @Override
    public boolean isKnownOrg(String orgId) {
        return liveOrgs.contains(orgId);
    }

    @Override
    public void refresh() {
        refreshCount++;
        live.putAll(staged);
        liveOrgs.addAll(stagedOrgs);
        staged.clear();
        stagedOrgs.clear();
    }
}

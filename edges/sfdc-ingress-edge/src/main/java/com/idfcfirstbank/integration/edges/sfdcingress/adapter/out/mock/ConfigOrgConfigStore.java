package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock;

import com.idfcfirstbank.integration.edges.sfdcingress.config.EdgeProperties;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.OrgConfigPort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Org-config-as-data backed by {@link EdgeProperties} (Slice 1; the config store
 * behind this port arrives in a later slice). Routing and known orgs are DATA —
 * adding a business line or org is a config row. {@link #refresh()} re-reads the
 * bound properties, modelling the C2 unknown-org refresh-and-recheck.
 */
@Component
public class ConfigOrgConfigStore implements OrgConfigPort {

    private final EdgeProperties properties;
    private final Map<String, RoutingDecision> routesByType = new HashMap<>();
    private final Set<String> knownOrgs = new HashSet<>();

    public ConfigOrgConfigStore(EdgeProperties properties) {
        this.properties = properties;
        load();
    }

    private synchronized void load() {
        routesByType.clear();
        knownOrgs.clear();
        for (EdgeProperties.RouteRule rule : properties.routing()) {
            routesByType.put(rule.type(),
                    new RoutingDecision(SourceSystem.SFDC, rule.type(), rule.topic(), rule.downstreamJourney()));
        }
        knownOrgs.addAll(properties.knownOrgs());
    }

    @Override
    public synchronized Optional<RoutingDecision> resolveRouting(SourceSystem source, String typeCode) {
        return Optional.ofNullable(routesByType.get(typeCode));
    }

    @Override
    public synchronized boolean isKnownOrg(String orgId) {
        return knownOrgs.contains(orgId);
    }

    @Override
    public void refresh() {
        load();
    }
}

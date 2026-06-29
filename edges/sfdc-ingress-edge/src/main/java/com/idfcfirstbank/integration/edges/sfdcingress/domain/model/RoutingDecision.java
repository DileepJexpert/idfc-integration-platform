package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;

/**
 * Resolved routing for an event (punch list §E), sourced from org-config-as-data
 * via {@code OrgConfigPort} — never hardcoded. {@code (source=SFDC, type)} maps
 * to an origination topic + downstream journey.
 */
public record RoutingDecision(SourceSystem source, String typeCode, String topic, String downstreamJourney) {
}

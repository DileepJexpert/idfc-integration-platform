package com.idfcfirstbank.integration.sfdcresponse.domain.model;

/** Resolved per-org egress target (orgId -> response topic + SFDC object). Config-as-data. */
public record OrgResponse(String orgId, String responseTopic, String sfdcObject) {
}

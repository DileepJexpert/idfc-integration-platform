package com.idfcfirstbank.integration.sfdcresponse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Per-org SFDC egress config-as-data (spec v2 §B). New org = a row, not a service. */
@ConfigurationProperties(prefix = "idfc.sfdc-response")
public record SfdcResponseProperties(
        String notifyTopic,
        String group,
        String defaultResponseTopic,
        List<Org> orgs) {

    public SfdcResponseProperties {
        notifyTopic = notifyTopic == null ? "sfdc.response.notify.v1" : notifyTopic;
        defaultResponseTopic = defaultResponseTopic == null ? "sfdc.response.default.v1" : defaultResponseTopic;
        orgs = orgs == null ? List.of() : orgs;
    }

    public record Org(String orgId, String responseTopic, String sfdcObject) {}
}

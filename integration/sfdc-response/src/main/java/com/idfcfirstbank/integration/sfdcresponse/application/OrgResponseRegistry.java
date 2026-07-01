package com.idfcfirstbank.integration.sfdcresponse.application;

import com.idfcfirstbank.integration.sfdcresponse.config.SfdcResponseProperties;
import com.idfcfirstbank.integration.sfdcresponse.domain.model.OrgResponse;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * orgId -> {@link OrgResponse}, from config-as-data. An unknown org falls back to the
 * default response topic (visible, never dropped) — the consolidation of the per-org
 * services means adding an org is a config row here.
 */
@Component
public class OrgResponseRegistry {

    private final Map<String, OrgResponse> byOrg = new HashMap<>();
    private final String defaultTopic;

    public OrgResponseRegistry(SfdcResponseProperties properties) {
        this.defaultTopic = properties.defaultResponseTopic();
        for (SfdcResponseProperties.Org o : properties.orgs()) {
            byOrg.put(o.orgId(), new OrgResponse(o.orgId(), o.responseTopic(), o.sfdcObject()));
        }
    }

    public OrgResponse forOrg(String orgId) {
        OrgResponse resolved = orgId == null ? null : byOrg.get(orgId);
        return resolved != null ? resolved
                : new OrgResponse(orgId, defaultTopic, "Integration_Message__c");
    }
}

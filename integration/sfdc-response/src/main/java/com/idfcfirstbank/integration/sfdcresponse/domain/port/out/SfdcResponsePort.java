package com.idfcfirstbank.integration.sfdcresponse.domain.port.out;

import com.idfcfirstbank.integration.sfdcresponse.domain.model.OrgResponse;

import java.util.Map;

/** Deliver a decision/notification to the resolved per-org SFDC response. Mocked locally. */
public interface SfdcResponsePort {
    void deliver(OrgResponse target, Map<String, Object> notification);
}

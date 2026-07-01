package com.idfcfirstbank.integration.sfdcresponse.application;

import com.idfcfirstbank.integration.sfdcresponse.domain.model.OrgResponse;
import com.idfcfirstbank.integration.sfdcresponse.domain.port.out.SfdcResponsePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Route a success decision or failure notification to the right per-org SFDC response
 * (spec v2 §B). Resolves orgId -> per-org config and delivers. PII: logs ids + outcome
 * only, never the payload body.
 */
@Service
public class SfdcResponseService {

    private static final Logger log = LoggerFactory.getLogger(SfdcResponseService.class);

    private final OrgResponseRegistry registry;
    private final SfdcResponsePort responsePort;

    public SfdcResponseService(OrgResponseRegistry registry, SfdcResponsePort responsePort) {
        this.registry = registry;
        this.responsePort = responsePort;
    }

    public void onNotification(Map<String, Object> notification) {
        String orgId = str(notification.get("orgId"));
        OrgResponse target = registry.forOrg(orgId);
        responsePort.deliver(target, notification);
        log.info("sfdc-response.delivered orgId={} topic={} outcome={} correlationId={}",
                orgId, target.responseTopic(), notification.get("outcome"), notification.get("correlationId"));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

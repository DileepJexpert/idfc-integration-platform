package com.idfcfirstbank.integration.sfdcresponse.application;

import com.idfcfirstbank.integration.sfdcresponse.config.SfdcResponseProperties;
import com.idfcfirstbank.integration.sfdcresponse.config.SfdcResponseProperties.Org;
import com.idfcfirstbank.integration.sfdcresponse.domain.model.OrgResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-org fan-out from config-as-data (spec v2 §B): known org -> its topic; unknown -> default (never dropped). */
class SfdcResponseServiceTest {

    private record Delivered(String topic, Object outcome) {}

    private final List<Delivered> out = new ArrayList<>();
    private final SfdcResponseProperties props = new SfdcResponseProperties(
            "sfdc.response.notify.v1", "sfdc-response", "sfdc.response.default.v1",
            List.of(new Org("00DC40000014dS1MAI", "sfdc.response.cd.v1", "Integration_Message__c")));

    private final SfdcResponseService service = new SfdcResponseService(
            new OrgResponseRegistry(props),
            (OrgResponse t, Map<String, Object> n) -> out.add(new Delivered(t.responseTopic(), n.get("outcome"))));

    @Test
    void knownOrgRoutesToItsConfiguredResponseTopic() {
        service.onNotification(Map.of("orgId", "00DC40000014dS1MAI", "outcome", "FAILED", "correlationId", "c1"));
        assertThat(out).singleElement().satisfies(d -> {
            assertThat(d.topic()).isEqualTo("sfdc.response.cd.v1");
            assertThat(d.outcome()).isEqualTo("FAILED");
        });
    }

    @Test
    void unknownOrgFallsBackToDefaultTopicNeverDropped() {
        service.onNotification(Map.of("orgId", "UNKNOWN_ORG", "outcome", "APPROVED", "correlationId", "c2"));
        assertThat(out).singleElement().satisfies(d -> assertThat(d.topic()).isEqualTo("sfdc.response.default.v1"));
    }
}

package com.idfcfirstbank.integration.capabilities.bureau.application;

import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReport;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BureauServiceTest {

    private CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-1", "corr-1", "bureau", "n_bureau", payload, Map.of());
    }

    @Test
    void fetchesReportAndMapsResult() {
        BureauService service = new BureauService(new MockCibilAdapter());
        CapabilityResponse resp = service.handle(request(Map.of("pan", "ABCDE1234F", "name", "Asha")));

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.capabilityKey()).isEqualTo("bureau");
        assertThat(resp.nodeId()).isEqualTo("n_bureau");
        assertThat(resp.result()).containsEntry("bureauScore", 780);
        assertThat(resp.result()).containsEntry("bureauGrade", "A");
        assertThat(resp.result()).containsEntry("reportId", "CIBIL-ABCDE1234F");
    }

    @Test
    void highPanScoresHighLowPanScoresLow() {
        BureauService service = new BureauService(new MockCibilAdapter());

        CapabilityResponse high = service.handle(request(Map.of("pan", "ABCDE1234F")));
        assertThat(high.result()).containsEntry("bureauScore", 780);
        assertThat(high.result()).containsEntry("bureauGrade", "A");

        CapabilityResponse low = service.handle(request(Map.of("pan", "LOWPAN999Z")));
        assertThat(low.result()).containsEntry("bureauScore", 540);
        assertThat(low.result()).containsEntry("bureauGrade", "C");
    }

    @Test
    void cibilFailureYieldsErrorResponse() {
        CibilPort failing = identity -> {
            throw new RuntimeException("cibil down");
        };
        BureauService service = new BureauService(failing);
        CapabilityResponse resp = service.handle(request(Map.of("pan", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void mockAdapterIsDeterministic() {
        BureauReport a = new MockCibilAdapter().fetch(Map.of("pan", "P1"));
        BureauReport b = new MockCibilAdapter().fetch(Map.of("pan", "P1"));
        assertThat(a).isEqualTo(b);
        assertThat(a.reportId()).isEqualTo("CIBIL-P1");
    }
}

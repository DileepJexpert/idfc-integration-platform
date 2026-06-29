package com.idfcfirstbank.integration.capabilities.bureau.application;

import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial.MockCommercialBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau.MockMultiBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.BureauVendorPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BureauServiceTest {

    private static final List<BureauVendorPort> VENDORS = List.of(
            new MockCibilAdapter(), new MockMultiBureauAdapter(), new MockCommercialBureauAdapter());

    private BureauService service(List<BureauType> defaults) {
        return new BureauService(new BureauFetchService(VENDORS), defaults);
    }

    private CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-1", "corr-1", "bureau", "n_bureau", payload, Map.of());
    }

    @Test
    void fetchesCibilAndMapsPrimaryResult() {
        CapabilityResponse resp = service(List.of(BureauType.CIBIL))
                .handle(request(Map.of("pan", "ABCDE1234F", "name", "Asha")));

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        // Primary fields scoring + the branch read by name:
        assertThat(resp.result()).containsEntry("bureauScore", 780);
        assertThat(resp.result()).containsEntry("bureauGrade", "A");
        assertThat(resp.result()).containsEntry("reportId", "CIBIL-ABCDE1234F");
        // The canonical multi-bureau list is present (one entry for CIBIL):
        assertThat((List<?>) resp.result().get("bureauResults")).hasSize(1);
    }

    @Test
    void highPanScoresHighLowPanScoresLow() {
        BureauService service = service(List.of(BureauType.CIBIL));
        assertThat(service.handle(request(Map.of("pan", "ABCDE1234F"))).result())
                .containsEntry("bureauScore", 780).containsEntry("bureauGrade", "A");
        assertThat(service.handle(request(Map.of("pan", "LOWPAN999Z"))).result())
                .containsEntry("bureauScore", 540).containsEntry("bureauGrade", "C");
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiBureauRequestFansOutAndKeepsConservativePrimary() {
        CapabilityResponse resp = service(List.of(BureauType.CIBIL))
                .handle(request(Map.of("pan", "ABCDE1234F",
                        "bureauTypes", List.of("CIBIL", "MULTI_BUREAU", "COMMERCIAL"))));

        List<Map<String, Object>> results = (List<Map<String, Object>>) resp.result().get("bureauResults");
        assertThat(results).hasSize(3);
        assertThat(results).extracting(r -> r.get("type"))
                .containsExactly("CIBIL", "COMMERCIAL", "MULTI_BUREAU"); // sorted by type name
        // Primary = CIBIL (780), even though COMMERCIAL (760) is lower.
        assertThat(resp.result()).containsEntry("bureauScore", 780);
    }

    @Test
    void vendorFailureYieldsErrorResponse() {
        BureauVendorPort failing = new BureauVendorPort() {
            public BureauType type() { return BureauType.CIBIL; }
            public com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult
            fetch(Map<String, Object> identity) {
                throw new RuntimeException("cibil down");
            }
        };
        BureauService service = new BureauService(new BureauFetchService(List.of(failing)), List.of(BureauType.CIBIL));
        assertThat(service.handle(request(Map.of("pan", "X"))).status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void mockAdapterIsDeterministic() {
        var a = new MockCibilAdapter().fetch(Map.of("pan", "P1"));
        var b = new MockCibilAdapter().fetch(Map.of("pan", "P1"));
        assertThat(a.score()).isEqualTo(b.score());
        assertThat(a.reportId()).isEqualTo("CIBIL-P1");
    }
}

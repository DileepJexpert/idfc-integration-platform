package com.idfcfirstbank.integration.capabilities.customer.party.application;

import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.MockPosidexAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerPartyServiceTest {

    private CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-1", "corr-1", "customer-party", "n_customer", payload, Map.of());
    }

    @Test
    void resolvesCustomerAndMapsResult() {
        CustomerPartyService service = new CustomerPartyService(new MockPosidexAdapter());
        CapabilityResponse resp = service.handle(request(Map.of("pan", "ABCDE1234F", "name", "Asha")));

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.capabilityKey()).isEqualTo("customer-party");
        assertThat(resp.nodeId()).isEqualTo("n_customer");
        assertThat(resp.result()).containsEntry("crn", "CRN-ABCDE1234F");
        assertThat(resp.result()).containsEntry("customerStatus", "ACTIVE");
    }

    @Test
    void posidexFailureYieldsErrorResponse() {
        PosidexPort failing = identity -> {
            throw new RuntimeException("posidex down");
        };
        CustomerPartyService service = new CustomerPartyService(failing);
        CapabilityResponse resp = service.handle(request(Map.of("pan", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void mockAdapterIsDeterministic() {
        CustomerProfile a = new MockPosidexAdapter().resolve(Map.of("pan", "P1"));
        CustomerProfile b = new MockPosidexAdapter().resolve(Map.of("pan", "P1"));
        assertThat(a).isEqualTo(b);
        assertThat(a.crn()).isEqualTo("CRN-P1");
    }
}

package com.idfcfirstbank.integration.capabilities.kyc.application;

import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.MockNsdlAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.domain.model.KycResult;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KycServiceTest {

    private CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-1", "corr-1", "kyc", "n_kyc", payload, Map.of());
    }

    @Test
    void verifiesKycAndMapsResult() {
        KycService service = new KycService(new MockNsdlAdapter());
        CapabilityResponse resp = service.handle(request(Map.of("pan", "ABCDE1234F", "name", "Asha")));

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.capabilityKey()).isEqualTo("kyc");
        assertThat(resp.nodeId()).isEqualTo("n_kyc");
        assertThat(resp.result()).containsEntry("kycStatus", "VERIFIED");
        assertThat(resp.result()).containsEntry("kycRefId", "KYC-ABCDE1234F");
    }

    @Test
    void nsdlFailureYieldsErrorResponse() {
        NsdlPort failing = identity -> {
            throw new RuntimeException("nsdl down");
        };
        KycService service = new KycService(failing);
        CapabilityResponse resp = service.handle(request(Map.of("pan", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void mockAdapterIsDeterministic() {
        KycResult a = new MockNsdlAdapter().verify(Map.of("pan", "P1"));
        KycResult b = new MockNsdlAdapter().verify(Map.of("pan", "P1"));
        assertThat(a).isEqualTo(b);
        assertThat(a.kycRefId()).isEqualTo("KYC-P1");
    }
}

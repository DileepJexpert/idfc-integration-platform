package com.idfcfirstbank.integration.capabilities.customer.party.application;

import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.MockPosidexAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.PosidexHttpAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void unclassifiedPosidexFailureIsErrorResponseClassifiedPermanent() {
        PosidexPort failing = identity -> {
            throw new RuntimeException("posidex down");
        };
        CapabilityResponse resp = new CustomerPartyService(failing).handle(request(Map.of("pan", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
        // An unclassified failure is conservatively PERMANENT (don't blind-retry an unknown).
        assertThat(resp.errorClass()).isEqualTo(ErrorClass.PERMANENT);
    }

    @Test
    void transientPosidexFailureIsPreserved_notCollapsedToPermanent() {
        // The bug: a down posidex (transport failure) was reported PERMANENT, so the
        // engine DLQ'd instead of retrying. The service must carry the port's TRANSIENT
        // classification through untouched.
        PosidexPort down = identity -> {
            throw new CapabilityException(ErrorClass.TRANSIENT, "posidex unreachable");
        };
        CapabilityResponse resp = new CustomerPartyService(down).handle(request(Map.of("pan", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
        assertThat(resp.errorClass()).isEqualTo(ErrorClass.TRANSIENT);
    }

    @Test
    void httpAdapterClassifiesAnUnreachableVendorAsRetryable_notPermanent() {
        // Nothing listens on localhost:1 -> connect refused -> a RETRYABLE class, never
        // PERMANENT. Proves the mislabel is fixed at the transport boundary too.
        PosidexHttpAdapter adapter = new PosidexHttpAdapter("http://localhost:1");
        assertThatThrownBy(() -> adapter.resolve(Map.of("pan", "ABCDE1234F")))
                .isInstanceOfSatisfying(CapabilityException.class, ex ->
                        assertThat(ex.errorClass())
                                .isIn(ErrorClass.TRANSIENT, ErrorClass.AMBIGUOUS));
    }

    @Test
    void mockAdapterIsDeterministic() {
        CustomerProfile a = new MockPosidexAdapter().resolve(Map.of("pan", "P1"));
        CustomerProfile b = new MockPosidexAdapter().resolve(Map.of("pan", "P1"));
        assertThat(a).isEqualTo(b);
        assertThat(a.crn()).isEqualTo("CRN-P1");
    }
}

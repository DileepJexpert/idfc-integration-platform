package com.idfcfirstbank.integration.capabilities.lending.origination.application;

import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.MockFinnOneAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LendingOriginationServiceTest {

    private CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-1", "corr-1", "lending-origination", "n_booking", payload, Map.of());
    }

    @Test
    void booksLoanAndMapsResult() {
        LendingOriginationService service = new LendingOriginationService(new MockFinnOneAdapter());
        CapabilityResponse resp = service.handle(request(Map.of("applicationRef", "APP-42", "crn", "CRN-1")));

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.capabilityKey()).isEqualTo("lending-origination");
        assertThat(resp.nodeId()).isEqualTo("n_booking");
        assertThat(resp.result()).containsEntry("loanId", "LN-APP-42");
        assertThat(resp.result()).containsEntry("status", "BOOKED");
        assertThat(String.valueOf(resp.result().get("loanId"))).startsWith("LN-");
    }

    @Test
    void finnOneFailureYieldsErrorResponse() {
        FinnOneBookingPort failing = application -> {
            throw new RuntimeException("finnone down");
        };
        LendingOriginationService service = new LendingOriginationService(failing);
        CapabilityResponse resp = service.handle(request(Map.of("applicationRef", "X")));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void mockAdapterIsDeterministic() {
        LoanBooking a = new MockFinnOneAdapter().book(Map.of("applicationRef", "A1"));
        LoanBooking b = new MockFinnOneAdapter().book(Map.of("applicationRef", "A1"));
        assertThat(a).isEqualTo(b);
        assertThat(a.loanId()).isEqualTo("LN-A1");
        assertThat(a.status()).isEqualTo("BOOKED");
    }
}

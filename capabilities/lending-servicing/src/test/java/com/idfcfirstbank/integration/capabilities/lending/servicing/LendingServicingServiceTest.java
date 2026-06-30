package com.idfcfirstbank.integration.capabilities.lending.servicing;

import com.idfcfirstbank.integration.capabilities.lending.servicing.adapter.out.store.InMemoryClosureStore;
import com.idfcfirstbank.integration.capabilities.lending.servicing.application.LendingServicingService;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.CommHubPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.FinnOneForeclosurePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.MssfPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcCasePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcPartnerPaymentPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LendingServicingServiceTest {

    private final AtomicInteger caseCreations = new AtomicInteger();

    private LendingServicingService service() {
        FinnOneForeclosurePort finnOne = lan -> lan.contains("DUE") ? 1500.0 : 0.0;
        SfdcCasePort sfdc = lan -> { caseCreations.incrementAndGet(); return "CASE-" + lan; };
        SfdcPartnerPaymentPort pay = lan -> lan.contains("PAID");
        CommHubPort comm = (lan, msg) -> { };
        MssfPort mssf = (kind, ref) -> Map.of("kind", kind, "value", "OK");
        return new LendingServicingService(finnOne, sfdc, pay, comm, mssf, new InMemoryClosureStore());
    }

    private static CapabilityRequest req(Map<String, Object> payload) {
        return new CapabilityRequest("ji", "corr", "lending-servicing", "n", payload, Map.of());
    }

    @Test
    void batchClosureCreatesCaseWhenForeclosureAmountIsZero() {
        assertThat(service().batchClosure(req(Map.of("lan", "LAN-1"))))
                .containsEntry("status", "SFDC_CREATED").containsKey("sfdcCaseId");
    }

    @Test
    void batchClosureFailsValidationWhenAmountDue() {
        assertThat(service().batchClosure(req(Map.of("lan", "LAN-DUE"))))
                .containsEntry("status", "VALIDATION_FAILED");
    }

    @Test
    void processClosedLoanIsIdempotentOnLan() {
        LendingServicingService s = service();
        s.processClosedLoan(req(Map.of("lan", "LAN-2")));
        assertThat(s.processClosedLoan(req(Map.of("lan", "LAN-2")))).containsEntry("duplicate", true);
        assertThat(caseCreations.get()).as("SFDC case created once").isEqualTo(1);
    }

    @Test
    void excessAmountNotifiesCommHubWhenNoPartnerPayment() {
        assertThat(service().processExcessAmount(req(Map.of("lan", "LAN-3"))))
                .containsEntry("partnerPayment", false).containsEntry("notified", true);
        assertThat(service().processExcessAmount(req(Map.of("lan", "LAN-PAID"))))
                .containsEntry("partnerPayment", true).containsEntry("notified", false);
    }

    @Test
    void getMarutiGoesThroughMssf() {
        assertThat(service().getMaruti(req(Map.of("loanRef", "ML-1", "kind", "DOC_STATUS"))))
                .containsEntry("kind", "DOC_STATUS").containsKey("result");
    }
}

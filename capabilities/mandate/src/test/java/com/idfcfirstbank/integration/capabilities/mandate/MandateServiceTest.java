package com.idfcfirstbank.integration.capabilities.mandate;

import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.store.InMemoryMandateStore;
import com.idfcfirstbank.integration.capabilities.mandate.application.MandateService;
import com.idfcfirstbank.integration.capabilities.mandate.domain.model.MandateStatus;
import com.idfcfirstbank.integration.capabilities.mandate.domain.model.Vendor;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.AutopayLinkPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.CbsNachPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.EnachVerificationPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateEventPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateStorePort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.VendorMandatePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MandateServiceTest {

    private final AtomicInteger vendorCalls = new AtomicInteger();
    private final List<String> emitted = new ArrayList<>();
    private final MandateStorePort store = new InMemoryMandateStore();

    private MandateService service() {
        VendorMandatePort vendor = (v, inv, m) -> {
            vendorCalls.incrementAndGet();
            return "REG-" + inv;
        };
        EnachVerificationPort enach = (inv, m) -> "VERIFIED";
        AutopayLinkPort autopay = (inv, m) -> "https://idfcfirst.in/p/" + inv;
        CbsNachPort cbs = new CbsNachPort() {
            public boolean enquire(String inv) { return !inv.contains("MISSING"); }
            public void cancel(String inv) { }
        };
        MandateEventPort events = (inv, e) -> emitted.add(inv);
        return new MandateService(vendor, enach, autopay, cbs, store, events);
    }

    private static CapabilityRequest req(Map<String, Object> payload) {
        return new CapabilityRequest("ji", "corr", "mandate", "n", payload, Map.of());
    }

    @Test
    void registerPersistsPendingAndReturnsRef() {
        Map<String, Object> out = service().register(req(Map.of("invoiceNo", "INV-1", "vendor", "DIGIO")));
        assertThat(out).containsEntry("status", "PENDING").containsKey("registrationRef");
        assertThat(store.find("INV-1")).get()
                .satisfies(t -> assertThat(t.vendor()).isEqualTo(Vendor.DIGIO));
        assertThat(vendorCalls.get()).isEqualTo(1);
    }

    @Test
    void registerIsIdempotentOnInvoiceNo() {
        MandateService s = service();
        s.register(req(Map.of("invoiceNo", "INV-2")));
        Map<String, Object> second = s.register(req(Map.of("invoiceNo", "INV-2")));
        assertThat(second).containsEntry("duplicate", true);
        assertThat(vendorCalls.get()).as("vendor called once across two registers").isEqualTo(1);
    }

    @Test
    void verifyEnachReturnsStatus() {
        assertThat(service().verifyEnach(req(Map.of("invoiceNo", "INV-3"))))
                .containsEntry("enachStatus", "VERIFIED");
    }

    @Test
    void setupAutopayLinkSendsLink() {
        assertThat(service().setupAutopayLink(req(Map.of("invoiceNo", "INV-4"))))
                .containsEntry("sent", true).containsKey("autopayLink");
    }

    @Test
    void cancelBranchesOnCbsEnquiry() {
        MandateService s = service();
        assertThat(s.cancel(req(Map.of("invoiceNo", "INV-5")))).containsEntry("found", true)
                .containsEntry("cancelled", true);
        assertThat(s.cancel(req(Map.of("invoiceNo", "INV-MISSING")))).containsEntry("found", false);
    }

    @Test
    void callbackUpdatesStateAndEmitsEvent() {
        MandateService s = service();
        s.register(req(Map.of("invoiceNo", "INV-6")));
        Map<String, Object> out = s.handleVendorCallback(req(Map.of("invoiceNo", "INV-6", "status", "SUCCESS")));
        assertThat(out).containsEntry("processed", true).containsEntry("status", "SUCCESS");
        assertThat(store.find("INV-6")).get()
                .satisfies(t -> assertThat(t.status()).isEqualTo(MandateStatus.SUCCESS));
        assertThat(emitted).containsExactly("INV-6"); // MandateCallback emitted, correlation=invoiceNo
    }
}

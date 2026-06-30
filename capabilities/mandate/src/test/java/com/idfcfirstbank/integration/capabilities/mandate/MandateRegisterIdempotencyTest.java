package com.idfcfirstbank.integration.capabilities.mandate;

import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.store.InMemoryMandateStore;
import com.idfcfirstbank.integration.capabilities.mandate.application.MandateService;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.AutopayLinkPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.CbsNachPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.EnachVerificationPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateEventPort;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.VendorMandatePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mandate's business exactly-once gate: 32 threads register the SAME invoiceNo;
 * the store's atomic insert-if-absent admits one winner so the VENDOR is called
 * EXACTLY ONCE. For mandates (a money-adjacent registration), this single-call
 * guarantee is the whole ballgame.
 */
class MandateRegisterIdempotencyTest {

    @Test
    void concurrentRegisterSameInvoiceCallsVendorOnce() throws Exception {
        AtomicInteger vendorCalls = new AtomicInteger();
        VendorMandatePort vendor = (v, inv, m) -> {
            vendorCalls.incrementAndGet();
            return "REG-" + inv;
        };
        EnachVerificationPort enach = (inv, m) -> "VERIFIED";
        AutopayLinkPort autopay = (inv, m) -> "link";
        CbsNachPort cbs = new CbsNachPort() {
            public boolean enquire(String inv) { return true; }
            public void cancel(String inv) { }
        };
        MandateEventPort events = (inv, e) -> { };
        MandateService service = new MandateService(vendor, enach, autopay, cbs,
                new InMemoryMandateStore(), events);

        CapabilityRequest req = new CapabilityRequest(
                "ji", "corr", "mandate", "n", Map.of("invoiceNo", "INV-RACE", "vendor", "DIGIO"), Map.of());

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    service.register(req);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();

        assertThat(vendorCalls.get()).as("vendor registered exactly once").isEqualTo(1);
    }
}

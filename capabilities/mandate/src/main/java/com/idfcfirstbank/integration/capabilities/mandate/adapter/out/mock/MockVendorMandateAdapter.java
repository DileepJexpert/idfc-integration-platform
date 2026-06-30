package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock;

import com.idfcfirstbank.integration.capabilities.mandate.domain.model.Vendor;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.VendorMandatePort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Mock Digio/Ingenico (via Kong) — returns a deterministic registration ref.
 * Real HTTP-over-Kong + JWE encryption is a config-driven later step (§10). */
@Component
public class MockVendorMandateAdapter implements VendorMandatePort {
    @Override
    public String register(Vendor vendor, String invoiceNo, Map<String, Object> mandate) {
        return "REG-" + vendor.name() + "-" + invoiceNo;
    }
}

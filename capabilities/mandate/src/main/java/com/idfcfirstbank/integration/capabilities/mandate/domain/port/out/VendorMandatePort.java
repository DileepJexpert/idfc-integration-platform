package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

import com.idfcfirstbank.integration.capabilities.mandate.domain.model.Vendor;

import java.util.Map;

/** Register a mandate with the vendor (Digio/Ingenico) via Kong. Mocked locally. */
public interface VendorMandatePort {
    /** @return the vendor registration reference. */
    String register(Vendor vendor, String invoiceNo, Map<String, Object> mandate);
}

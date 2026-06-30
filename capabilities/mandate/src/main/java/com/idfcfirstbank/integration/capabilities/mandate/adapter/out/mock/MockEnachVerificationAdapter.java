package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock;

import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.EnachVerificationPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Mock ENACH/NPCI — VERIFIED by default; REJECTED when invoiceNo contains "REJECT". */
@Component
public class MockEnachVerificationAdapter implements EnachVerificationPort {
    @Override
    public String verify(String invoiceNo, Map<String, Object> mandate) {
        return invoiceNo != null && invoiceNo.toUpperCase().contains("REJECT") ? "REJECTED" : "VERIFIED";
    }
}

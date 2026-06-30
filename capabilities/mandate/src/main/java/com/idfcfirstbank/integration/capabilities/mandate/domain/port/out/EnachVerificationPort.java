package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

import java.util.Map;

/** Verify an ENACH mandate via ENACH Elite Services / NPCI. Mocked locally. */
public interface EnachVerificationPort {
    /** @return verification status, e.g. "VERIFIED" / "REJECTED". */
    String verify(String invoiceNo, Map<String, Object> mandate);
}

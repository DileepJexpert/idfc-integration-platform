package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

import java.util.Map;

/**
 * MSSF (Maruti) adapter (BRD §4a) — a pure transport+encryption bridge to MSSF
 * via Kong. Owns nothing. {@code kind} is LOAN_STATUS | DOC_STATUS | DOC_ACK.
 * Behaviour (mocked): Kong token -> AES-encrypt -> POST via Kong -> decrypt.
 */
public interface MssfPort {
    Map<String, Object> call(String kind, String loanRef);
}

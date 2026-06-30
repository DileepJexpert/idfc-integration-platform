package com.idfcfirstbank.integration.capabilities.lending.origination.domain.port;

import java.util.Map;

/**
 * Pre-disbursement EMI validation against a consumer-electronics brand API
 * (BRD §5). Config-driven (brand-config/{brand}.json + a pass/fail rule); owns
 * nothing. Mocked locally (real brand API + EntAuth/Kong is a later step).
 */
public interface BrandValidationPort {
    /** @return e.g. {@code {pass: "Y"|"N", brand: ..., reason: ...}}. */
    Map<String, Object> validate(String brand, Map<String, Object> devicePayload);
}

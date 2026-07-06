package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model;

import java.util.Map;

/**
 * The IMPS fund-transfer request, exactly the fields the real INDMONEY curl sends.
 * {@code idempotentId} is the caller-supplied key that must prevent a double
 * transfer — it is REQUIRED for a money movement (a transfer with no idempotency
 * key has no double-spend protection).
 */
public record ImpsFtRequest(
        String custBankAccNo,
        String ifscCode,
        String reqId,
        String loanNo,
        String isDisbursalFlag,
        String idempotentId,
        String source) {

    /** Build from the opaque JSON body the controller received (partner contract = data). */
    public static ImpsFtRequest fromPayload(Map<String, Object> p) {
        return new ImpsFtRequest(
                str(p, "custBankAccNo"),
                str(p, "ifscCode"),
                str(p, "reqId"),
                str(p, "loanNo"),
                str(p, "isDisbursalFlag"),
                str(p, "idempotentId"),
                str(p, "source"));
    }

    /** The idempotency key for this transfer — blank/missing is not acceptable for a money movement. */
    public boolean hasIdempotencyKey() {
        return idempotentId != null && !idempotentId.isBlank();
    }

    /** The vendor request body (real HTTP to the IMPS backend). */
    public Map<String, Object> toVendorBody() {
        return Map.of(
                "custBankAccNo", nullToEmpty(custBankAccNo),
                "ifscCode", nullToEmpty(ifscCode),
                "reqId", nullToEmpty(reqId),
                "loanNo", nullToEmpty(loanNo),
                "isDisbursalFlag", nullToEmpty(isDisbursalFlag),
                "idempotentId", nullToEmpty(idempotentId),
                "source", nullToEmpty(source));
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The IMPS fund-transfer result, exactly the shape the real backend returns:
 * {@code status:"S"} = the transfer succeeded; anything else (with
 * {@code errCode}/{@code errMessage} populated) is a BUSINESS "no" — a real
 * outcome to return to the caller, NOT a technical exception. A transport failure
 * (timeout/5xx) never produces one of these — it throws
 * {@link com.idfcfirstbank.integration.shared.sync.SyncTechnicalException}.
 */
public record ImpsFtResult(
        String reqId,
        String status,
        String transactionId,
        String custBankAccNo,
        String customerName,
        String errCode,
        String errMessage) {

    public static final String SUCCESS = "S";

    /** Parse the vendor's JSON response body into the typed result. */
    public static ImpsFtResult fromVendorBody(Map<String, Object> b) {
        return new ImpsFtResult(
                str(b, "reqId"),
                str(b, "status"),
                str(b, "transactionId"),
                str(b, "custBankAccNo"),
                str(b, "customerName"),
                str(b, "errCode"),
                str(b, "errMessage"));
    }

    /** A definitive "the money moved" outcome. Non-success is a business decline. */
    public boolean isSuccess() {
        return SUCCESS.equals(status);
    }

    /** The response body returned to the caller on the same sync call. */
    public Map<String, Object> toResponseBody() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reqId", reqId);
        m.put("status", status);
        m.put("transactionId", transactionId);
        m.put("custBankAccNo", custBankAccNo);
        m.put("customerName", customerName);
        m.put("errCode", errCode);
        m.put("errMessage", errMessage);
        return m;
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b == null ? null : b.get(k);
        return v == null ? null : String.valueOf(v);
    }
}

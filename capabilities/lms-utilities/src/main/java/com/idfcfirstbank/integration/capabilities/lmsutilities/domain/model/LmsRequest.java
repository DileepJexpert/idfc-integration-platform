package com.idfcfirstbank.integration.capabilities.lmsutilities.domain.model;

import java.util.Map;

/**
 * The LMS-utilities query request, exactly the fields the real callLmsUtilities curl
 * sends. {@code requestCode} (OFFER_CHECK, …) names the LMS operation the caller
 * wants; the service dispatches on it against a fail-closed known-code allow-list
 * before any backend call, so an unknown code never silently runs.
 */
public record LmsRequest(
        String entityName,
        String agreementId,
        String crnNo,
        String requestCode) {

    /** Build from the opaque JSON body the controller received (partner contract = data). */
    public static LmsRequest fromPayload(Map<String, Object> p) {
        return new LmsRequest(
                str(p, "entityName"),
                str(p, "agreementId"),
                str(p, "crnNo"),
                str(p, "requestCode"));
    }

    /** The vendor request body (real HTTP to the LMS-utilities backend). */
    public Map<String, Object> toVendorBody() {
        return Map.of(
                "entityName", nullToEmpty(entityName),
                "agreementId", nullToEmpty(agreementId),
                "crnNo", nullToEmpty(crnNo),
                "requestCode", nullToEmpty(requestCode));
    }

    private static String str(Map<String, Object> p, String k) {
        Object v = p == null ? null : p.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

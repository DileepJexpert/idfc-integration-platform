package com.idfcfirstbank.integration.capabilities.verification.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The universal verification response (mirrors SFDC {@code Integration_Message__c}):
 * {@code { ISSUCCESS, ERROR, DATA }}. Written to the journey context; the journey
 * branches first on ISSUCCESS then on service-specific DATA fields.
 */
public final class VerificationEnvelope {

    private VerificationEnvelope() {}

    public static Map<String, Object> success(Map<String, Object> data) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ISSUCCESS", "True");
        m.put("DATA", data == null ? Map.of() : data);
        return m;
    }

    public static Map<String, Object> failure(String errorCode, String errorDesc) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", errorCode);
        error.put("errorDesc", errorDesc);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ISSUCCESS", "False");
        m.put("ERROR", error);
        m.put("DATA", Map.of());
        return m;
    }
}

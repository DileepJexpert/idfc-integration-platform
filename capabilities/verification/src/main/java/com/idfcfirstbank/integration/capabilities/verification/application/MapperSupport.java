package com.idfcfirstbank.integration.capabilities.verification.application;

import java.util.Map;

/** Mapper helpers. {@link #firstOf} preserves the wrapper's ALT-FIELD-NAME tolerance
 *  (e.g. {@code registrationNumber} OR {@code reg_no}) — the second real behaviour. */
public final class MapperSupport {

    private MapperSupport() {}

    /** First present, non-blank value among the candidate keys, else null. */
    public static Object firstOf(Map<String, Object> src, String... keys) {
        if (src == null) return null;
        for (String k : keys) {
            Object v = src.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }
}

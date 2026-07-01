package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KARZA_VAHAN_RC response mapper (BRD B.1): flatten the raw Karza response
 * ({@code metadata} + {@code resource_data[]}) to the decision shape
 * {@code { Status, errorMessage, result:[ { result:{ rcStatus, blackListStatus, ... } } ] }}.
 * The branch reads {@code result[0].result.rcStatus/blackListStatus}. blackListStatus is
 * NORMALISED to CLEAR/<reason>; rcStatus falls back to metadata.status. Full field list
 * (20+) enriches with open input D#3 — the DECISION fields are mapped here.
 */
public class KarzaVahanRcResponseMapper implements Mapper {

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> map(Map<String, Object> raw) {
        Map<String, Object> metadata = asMap(raw == null ? null : raw.get("metadata"));
        List<Object> resourceData = asList(raw == null ? null : raw.get("resource_data"));
        Map<String, Object> first = resourceData.isEmpty() ? Map.of() : asMap(resourceData.get(0));

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("rcStatus", str(firstNonNull(first.get("rcStatus"), metadata.get("status"))));
        inner.put("blackListStatus", normaliseBlackList(first.get("blackListStatus")));
        inner.put("registrationNumber", first.get("registrationNumber"));
        inner.put("ownerName", first.get("ownerName"));
        inner.put("insuranceUpto", first.get("insuranceUpto"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Status", str(metadata.getOrDefault("status", "")));
        out.put("errorMessage", "");
        out.put("result", List.of(Map.of("result", inner)));
        return out;
    }

    /** Karza blackListStatus -> CLEAR when not blacklisted, else the raw reason (uppercased). */
    private static String normaliseBlackList(Object raw) {
        String v = raw == null ? "" : String.valueOf(raw).trim();
        if (v.isEmpty() || v.equalsIgnoreCase("NO") || v.equalsIgnoreCase("N")
                || v.equalsIgnoreCase("CLEAR") || v.equalsIgnoreCase("FALSE")) {
            return "CLEAR";
        }
        return v.toUpperCase();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

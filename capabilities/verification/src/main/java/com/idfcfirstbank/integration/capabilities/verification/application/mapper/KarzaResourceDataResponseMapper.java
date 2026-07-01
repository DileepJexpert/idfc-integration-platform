package com.idfcfirstbank.integration.capabilities.verification.application.mapper;

import com.idfcfirstbank.integration.capabilities.verification.application.Mapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic Karza response mapper: flatten {@code metadata} + {@code resource_data[]} (each
 * carrying {@code requestId}/{@code request_id}, {@code statusCode} and a nested {@code result})
 * to {@code { Status, errorMessage, result:[ { requestId, statusCode, result:{…} } ] }}. The
 * inner {@code result} is passed through UNINSPECTED — the branch reads the decision fields
 * (domain-check {@code result.result/data.disposable/…}, negative-area {@code result.is_negative}).
 * Reused across svcNames whose Karza envelope shares this shape (spec v2 D.2/D.3).
 */
public class KarzaResourceDataResponseMapper implements Mapper {

    @Override
    public Map<String, Object> map(Map<String, Object> raw) {
        Map<String, Object> metadata = asMap(raw == null ? null : raw.get("metadata"));
        List<Object> resourceData = asList(raw == null ? null : raw.get("resource_data"));

        List<Object> result = new ArrayList<>();
        for (Object element : resourceData) {
            Map<String, Object> el = asMap(element);
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("requestId", firstNonNull(el.get("requestId"), el.get("request_id")));
            mapped.put("statusCode", str(el.get("statusCode")));
            mapped.put("result", el.get("result"));
            result.add(mapped);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("Status", str(metadata.getOrDefault("status", "")));
        out.put("errorMessage", "");
        out.put("result", result);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) { return o instanceof List<?> l ? (List<Object>) l : List.of(); }
    private static Object firstNonNull(Object a, Object b) { return a != null ? a : b; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}

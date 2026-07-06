package com.idfcfirstbank.integration.shared.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The IDFC "house envelope" — {@code { metadata{status,message,version,time},
 * resource_data[…] }} — as ONE reusable mapper. LMS utilities, Karza, and future
 * sync services all speak this shape; normalize it here ONCE rather than hand-roll
 * per service.
 *
 * <p>{@code metadata.status == "SUCCESS"} is a success; the {@code resource_data}
 * rows pass through UNINSPECTED (the caller reads the domain fields). An empty or
 * absent {@code resource_data} on a success is a legitimate business "no result"
 * (e.g. "no offer") — a clean empty list, NOT an error.
 */
public final class HouseEnvelopeMapper {

    public static final String SUCCESS = "SUCCESS";

    /** Normalize a raw house-envelope body into a typed {@link HouseEnvelope}. */
    public HouseEnvelope map(Map<String, Object> raw) {
        Map<String, Object> metadata = asMap(raw == null ? null : raw.get("metadata"));
        List<Map<String, Object>> resourceData = asListOfMaps(raw == null ? null : raw.get("resource_data"));
        return new HouseEnvelope(
                str(metadata.get("status")),
                str(metadata.get("message")),
                str(metadata.get("version")),
                str(metadata.get("time")),
                resourceData);
    }

    /**
     * A normalized house response. {@link #isSuccess()} reflects
     * {@code metadata.status}; {@link #hasData()} distinguishes a success WITH rows
     * from a success with an empty {@code resource_data} (a business "no result").
     */
    public record HouseEnvelope(
            String status, String message, String version, String time,
            List<Map<String, Object>> resourceData) {

        public boolean isSuccess() {
            return SUCCESS.equalsIgnoreCase(status);
        }

        public boolean hasData() {
            return resourceData != null && !resourceData.isEmpty();
        }

        /** The response body returned to the caller (clean, camelCase, no raw metadata nesting). */
        public Map<String, Object> toResponseBody() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", status);
            m.put("message", message);
            m.put("resourceData", resourceData);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object el : list) {
                if (el instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

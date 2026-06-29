package in.idfc.integration.edges.sfdcingress.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inbound SFDC notification DTO (the HTTP shape of the SFDC outbound message).
 *
 * <p><b>A1 / applicationRef (blocking confirm):</b> {@code applicationRef} MUST be
 * an SFDC payload field that is STABLE across a resend of the same business
 * application (e.g. the Application/Opportunity Id) — it is the load-bearing half
 * of the composite fallback key. It is NOT invented and NOT derived from anything
 * request-scoped. If a future payload has no such stable field, that is a STOP/flag
 * condition, not a silent default. When absent, the fallback simply stays dormant
 * and dedupe runs on {@code notificationId} alone.
 *
 * <p>{@code correlationId} is trace-only and is NEVER a dedup input.
 */
public record SfdcNotificationRequest(
        String notificationId,
        String correlationId,
        String sfdcRecordId,
        String applicationRef,
        String orgId,
        String type,
        JsonNode payload) {

    boolean isStructurallyValid() {
        return notBlank(notificationId) && notBlank(orgId) && notBlank(type) && payload != null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

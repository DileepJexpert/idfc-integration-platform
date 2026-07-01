package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * The validated, framework-free representation of one inbound SFDC notification,
 * produced by the inbound adapter after authentication + schema validation. The
 * raw transport payload is carried as bytes for the S3 claim-check; the edge
 * does not interpret it (NO business logic).
 *
 * <p>{@code businessPayload} is the OPTIONAL already-parsed business body (e.g.
 * the SOAP path unwraps {@code Request__c} CDATA → {@code msgBdy} into a map).
 * When present, the normalizer rides it INLINE in the canonical envelope so the
 * engine reads the business fields straight into the journey context; when null
 * (e.g. the JSON path), only the claim-check {@code rawPayload} is used. This is
 * data carriage, not interpretation — the edge still has no business logic.
 *
 * @param notificationId        identity — "same business event?" (primary dedup key)
 * @param correlationId         trace only — new per request; NEVER a dedup input
 * @param sfdcRecordId          part of the composite fallback key
 * @param applicationRef        STABLE-across-resend business application reference
 * @param orgId                 originating org (routing/triage; config-as-data)
 * @param typeCode              business-line code (routed via OrgConfigPort)
 * @param rawPayload            opaque transport body (claim-checked to blob store)
 * @param payloadContentType    e.g. application/json
 * @param receivedAt            edge-assigned receipt time
 * @param businessPayload       OPTIONAL parsed business body carried inline (nullable)
 */
public record SfdcInboundEvent(
        String notificationId,
        String correlationId,
        String sfdcRecordId,
        String applicationRef,
        String orgId,
        String typeCode,
        byte[] rawPayload,
        String payloadContentType,
        Instant receivedAt,
        Map<String, Object> businessPayload) {

    /** Claim-check-only event (no inline business body). Preserves the original 9-arg shape. */
    public SfdcInboundEvent(String notificationId, String correlationId, String sfdcRecordId,
                            String applicationRef, String orgId, String typeCode, byte[] rawPayload,
                            String payloadContentType, Instant receivedAt) {
        this(notificationId, correlationId, sfdcRecordId, applicationRef, orgId, typeCode,
                rawPayload, payloadContentType, receivedAt, null);
    }

    public boolean hasApplicationFallback() {
        return sfdcRecordId != null && !sfdcRecordId.isBlank()
                && applicationRef != null && !applicationRef.isBlank();
    }
}

package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.time.Instant;

/**
 * The validated, framework-free representation of one inbound SFDC notification,
 * produced by the inbound adapter after authentication + schema validation. The
 * raw transport payload is carried as bytes for the S3 claim-check; the edge
 * does not interpret it (NO business logic).
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
        Instant receivedAt) {

    public boolean hasApplicationFallback() {
        return sfdcRecordId != null && !sfdcRecordId.isBlank()
                && applicationRef != null && !applicationRef.isBlank();
    }
}

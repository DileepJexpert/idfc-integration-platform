package com.idfcfirstbank.integration.shared.domain.envelope;

import java.time.Instant;

/**
 * The canonical origination envelope — THE shared contract between every channel
 * edge and the engine. Each edge normalizes its inbound shape into exactly this
 * and publishes it to the SAME origination topic, so the engine cannot tell which
 * edge sent it ("one platform, many doors"). An S3 claim-check ({@code payloadRef})
 * carries the body rather than inlining it.
 *
 * <p>This type lives in {@code shared-domain} precisely so the SFDC edge and the
 * digital-partner edge emit the IDENTICAL shape — proven by construction, not by
 * a fixture that can drift.
 */
public record CanonicalEnvelope(
        String transactionId,
        String schemaVersion,
        SourceSystem source,
        String type,
        String notificationId,
        String orgId,
        String sfdcRecordId,
        String applicationRef,
        String correlationId,
        String originalCorrelationId,
        String payloadRef,
        String payloadContentType,
        Instant occurredAt) {
}

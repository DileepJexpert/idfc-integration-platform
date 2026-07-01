package com.idfcfirstbank.integration.shared.domain.envelope;

import java.time.Instant;
import java.util.Map;

/**
 * The canonical origination envelope — THE shared contract between every channel
 * edge and the engine. Each edge normalizes its inbound shape into exactly this
 * and publishes it to the SAME origination topic, so the engine cannot tell which
 * edge sent it ("one platform, many doors").
 *
 * <p>The business body can travel two ways, and both are first-class:
 * <ul>
 *   <li><b>inline</b> {@code payload} — the parsed business fields ride in the
 *       envelope; the engine reads them straight into the journey context. This
 *       is the path for small bodies (e.g. the SFDC account-creation msgBdy).</li>
 *   <li><b>claim-check</b> {@code payloadRef} — a reference (e.g. S3) to a large
 *       body kept out-of-band; a downstream consumer fetches it by ref.</li>
 * </ul>
 * A given envelope may carry either or both; {@code payload} is nullable so every
 * existing claim-check-only call site keeps compiling (see the secondary ctor).
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
        Instant occurredAt,
        Map<String, Object> payload) {

    /**
     * Claim-check-only envelope (no inline body). Preserves the original 13-arg
     * shape so existing edges/DLQ construction compile unchanged.
     */
    public CanonicalEnvelope(String transactionId, String schemaVersion, SourceSystem source, String type,
                             String notificationId, String orgId, String sfdcRecordId, String applicationRef,
                             String correlationId, String originalCorrelationId, String payloadRef,
                             String payloadContentType, Instant occurredAt) {
        this(transactionId, schemaVersion, source, type, notificationId, orgId, sfdcRecordId, applicationRef,
                correlationId, originalCorrelationId, payloadRef, payloadContentType, occurredAt, null);
    }
}

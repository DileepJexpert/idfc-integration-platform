package in.idfc.integration.edges.sfdcingress.domain.model;

import java.time.Instant;

/**
 * The canonical envelope the edge normalizes every inbound event into and
 * publishes to Kafka. Uses an S3 claim-check ({@code payloadRef}) instead of
 * inlining the body. Platform-added fields ({@code transactionId},
 * {@code originalCorrelationId}) are on the parity allowlist (§F).
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

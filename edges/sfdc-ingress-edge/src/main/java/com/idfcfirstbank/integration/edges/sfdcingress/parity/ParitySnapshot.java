package com.idfcfirstbank.integration.edges.sfdcingress.parity;

import java.util.Arrays;

/**
 * The parity-relevant projection of an edge outcome (punch list §F). It contains
 * ONLY fields that must match between our edge and recorded Mule output:
 * dedup verdict, identity/routing fields, and the RESOLVED payload bytes.
 *
 * <p>The §F allowlist is encoded by ABSENCE: timestamps ({@code receivedAt},
 * {@code updatedAt}), per-request {@code correlationId}, field ORDERING, and
 * platform-added fields ({@code transactionId}, {@code originalCorrelationId})
 * are deliberately NOT here, so they can never register as a parity diff. The S3
 * claim-check indirection is also not a diff — {@code resolvedPayload} is the
 * fetched bytes, compared for byte-equality against Mule's inlined body.
 */
public record ParitySnapshot(
        String dedupVerdict,
        String notificationId,
        String orgId,
        String type,
        String sfdcRecordId,
        String applicationRef,
        String routingTopic,
        String downstreamJourney,
        byte[] resolvedPayload) {

    public boolean payloadEquals(ParitySnapshot other) {
        return Arrays.equals(resolvedPayload, other.resolvedPayload);
    }
}

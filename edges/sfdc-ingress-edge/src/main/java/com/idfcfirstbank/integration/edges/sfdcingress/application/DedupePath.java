package com.idfcfirstbank.integration.edges.sfdcingress.application;

/**
 * The dedupe paths (punch list / final flow §3). Exactly one applies to every
 * inbound event. Only {@link #NEW} and re-drives ({@link #STALLED},
 * {@link #FAILED}) may publish; only the CAS transition INTO DECIDED (elsewhere)
 * triggers the push-back.
 */
public enum DedupePath {
    /** First win for this dedup key — start the journey. */
    NEW,
    /** A resend while the original is ACTIVELY being processed (within the publish lease) — re-attach, no publish. */
    IN_FLIGHT,
    /**
     * A resend of a CRASHED attempt: the record is stuck in RECEIVED, or in
     * IN_FLIGHT past the publish lease, so the envelope was never confirmed on
     * Kafka. The resend must RE-DRIVE the publish — ACKing it as a duplicate
     * would silently lose the message forever.
     */
    STALLED,
    /** A resend after the envelope was confirmed published — idempotent, no re-publish. */
    PUBLISHED,
    /** A resend after the decision exists — idempotent, no publish, no push. */
    DECIDED,
    /** A resend after a prior failure — governed by C3/C5. */
    FAILED
}

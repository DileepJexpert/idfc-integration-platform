package in.idfc.integration.edges.sfdcingress.application;

/**
 * The FOUR dedupe paths (punch list / final flow §3). Exactly one applies to
 * every inbound event. Only {@link #NEW} starts a journey; only the CAS
 * transition INTO DECIDED (elsewhere) triggers the push-back — resend reads on
 * any of the other three never publish and never push.
 */
public enum DedupePath {
    /** First win for this dedup key — start the journey. */
    NEW,
    /** A resend while the original is still being processed — re-attach, no publish. */
    IN_FLIGHT,
    /** A resend after the decision exists — idempotent, no publish, no push. */
    DECIDED,
    /** A resend after a prior failure — governed by C3/C5. */
    FAILED
}

package com.idfcfirstbank.integration.shared.sync;

/**
 * The outcome of one sync-lane invocation, preserving the SAME business-vs-technical
 * distinction the async engine enforces:
 *
 * <ul>
 *   <li>{@link #SUCCESS} — the operation completed with a business "yes" (IMPS status S,
 *       an offer returned).</li>
 *   <li>{@link #BUSINESS_FAILURE} — a real business "no" (IMPS status ≠ S, a clean
 *       no-offer). NOT an error: a normal, expected outcome returned to the caller.</li>
 *   <li>{@link #TECHNICAL_ERROR} — a downstream timeout / 5xx / unreachable (a
 *       {@link SyncTechnicalException}). Never dressed up as success.</li>
 * </ul>
 */
public enum SyncOutcome {
    SUCCESS,
    BUSINESS_FAILURE,
    TECHNICAL_ERROR
}

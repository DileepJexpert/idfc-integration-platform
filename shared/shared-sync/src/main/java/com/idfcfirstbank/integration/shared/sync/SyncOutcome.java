package com.idfcfirstbank.integration.shared.sync;

/**
 * The outcome of one sync-lane invocation, preserving the SAME business-vs-technical
 * distinction the async engine enforces.
 *
 * <p><b>THE RULE (stated deliberately, not an accident of code):</b>
 * <ul>
 *   <li>{@link #SUCCESS} — the call worked and returned its TRUE answer. This
 *       <i>includes</i> a read/query that correctly returns a negative-but-valid
 *       result — an LMS <b>"no offer"</b>, an empty result set. The query succeeded;
 *       the answer is "none". That is not a failure, exactly as a valid decline is not
 *       an error. Marking a healthy "not pre-approved" as a failure would misread it.</li>
 *   <li>{@link #BUSINESS_FAILURE} — the business <b>declined/rejected a request to
 *       perform an action</b> (IMPS {@code status ≠ S} — a transfer refused). The call
 *       worked; the action was refused. Reserved for an actual rejection — never for a
 *       query that healthily returned "none".</li>
 *   <li>{@link #TECHNICAL_ERROR} — the call broke: downstream timeout / 5xx /
 *       unreachable, or a fail-closed unsupported request (a {@link SyncTechnicalException}).
 *       Never dressed up as success.</li>
 * </ul>
 *
 * <p>Consequence: a pure read capability (lms-utilities) has no BUSINESS_FAILURE case —
 * offer and no-offer are both SUCCESS; only a break is TECHNICAL_ERROR. An action
 * capability (imps-disbursal) uses BUSINESS_FAILURE for a refused transfer.
 */
public enum SyncOutcome {
    SUCCESS,
    BUSINESS_FAILURE,
    TECHNICAL_ERROR
}

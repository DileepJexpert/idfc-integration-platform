package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import java.util.Map;

/**
 * The partner's inbound origination request. {@code partner} is NOT here — it is
 * derived from the auth token (config), so it can't be spoofed in the body.
 *
 * @param requestId      the partner's idempotency key (primary dedup key)
 * @param applicationRef stable business application id (composite fallback key)
 * @param type           businessLine (PERSONAL_LOAN, LAP, ...)
 * @param orgId          owning org
 * @param payload        applicant data (identity, amount, ...)
 */
public record DigitalOriginationRequest(
        String requestId,
        String applicationRef,
        String type,
        String orgId,
        Map<String, Object> payload) {

    public boolean isStructurallyValid() {
        return notBlank(requestId) && notBlank(applicationRef) && notBlank(type) && notBlank(orgId);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}

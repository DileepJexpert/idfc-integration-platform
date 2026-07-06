package com.idfcfirstbank.integration.shared.sync;

/**
 * Validates the {@code Authorization: Bearer <ory_at_...>} token at the sync
 * ingress. FAIL CLOSED by contract: a missing/blank header, a non-Bearer scheme,
 * or an unaccepted token must be rejected — and with nothing configured, nothing
 * is accepted (no blank-token bypass). The dev impl checks a configured allow-list;
 * production swaps in real Ory/Hydra (Kong) token introspection behind this same
 * interface.
 */
public interface BearerTokenValidator {

    boolean isAuthorized(String authorizationHeader);
}

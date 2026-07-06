package com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.in.rest;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.config.ImpsDisbursalProperties;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates the {@code Authorization: Bearer <ory_at_...>} token at the sync
 * ingress. FAIL CLOSED: a missing/blank header, a non-Bearer scheme, or a token
 * not accepted is rejected — and if NO tokens are configured, nothing is accepted
 * (no blank-token bypass). The dev seam checks a configured allow-list; production
 * swaps the check for real Ory/Hydra (Kong) token introspection with the same
 * fail-closed contract.
 */
@Component
public class BearerTokenValidator {

    private static final String PREFIX = "Bearer ";

    private final Set<String> acceptedTokens;

    public BearerTokenValidator(ImpsDisbursalProperties props) {
        this.acceptedTokens = Set.copyOf(props.auth().acceptedTokens());
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return !token.isEmpty() && acceptedTokens.contains(token);
    }
}

package com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync;

import com.idfcfirstbank.integration.digitaledge.config.SyncEdgeProperties;
import com.idfcfirstbank.integration.shared.sync.BearerTokenValidator;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The dev {@link BearerTokenValidator}: accepts an {@code Authorization: Bearer
 * <token>} only if the token is on the configured allow-list. FAIL CLOSED — a
 * missing/blank header, a non-Bearer scheme, an unlisted token, or (crucially) an
 * EMPTY allow-list all reject. Production replaces this with a real Ory/Hydra
 * (Kong) introspection call behind the same interface.
 */
@Component
public class ConfiguredBearerTokenValidator implements BearerTokenValidator {

    private static final String PREFIX = "Bearer ";

    private final Set<String> acceptedTokens;

    public ConfiguredBearerTokenValidator(SyncEdgeProperties props) {
        this.acceptedTokens = Set.copyOf(props.auth().acceptedTokens());
    }

    @Override
    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return false;
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return !token.isEmpty() && acceptedTokens.contains(token);
    }
}

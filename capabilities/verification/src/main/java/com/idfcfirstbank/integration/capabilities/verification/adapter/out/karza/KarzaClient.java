package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.TokenProviderPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Map;

/**
 * Shared Karza HTTP client (OAuth Bearer POST + transport error classification), used by
 * every KARZA_* adapter — the per-svcName adapters differ only by svcName + endpoint, so
 * the call/classify logic lives here ONCE. Classification drives the retry engine (spec v2
 * §C): 4xx = PERMANENT, 5xx = TRANSIENT, read-timeout = AMBIGUOUS, connect-fail = TRANSIENT.
 * NO PII logged. Business declines are HTTP-200 bodies — NOT seen here (handled by the branch).
 */
@Component
public class KarzaClient {

    private final TokenProviderPort tokens;
    private final RestClient restClient = RestClient.create();

    public KarzaClient(TokenProviderPort tokens) {
        this.tokens = tokens;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(ResolvedRoute route, Map<String, Object> mappedRequest) {
        try {
            RestClient.RequestBodySpec spec = restClient.post()
                    .uri(URI.create(route.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON);
            if (route.authType() == AuthType.OAUTH_BEARER) {
                spec = spec.header("Authorization", "Bearer " + tokens.bearerToken(route.svcName()));
            }
            Map<String, Object> body = spec.body(mappedRequest).retrieve().body(Map.class);
            if (body == null) {
                throw new VerificationException(ErrorClass.PERMANENT, "EMPTY_RESPONSE", "karza returned empty body");
            }
            return body;
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new VerificationException(ec, "HTTP_" + e.getStatusCode().value(), "karza http error");
        } catch (ResourceAccessException e) {
            boolean readTimeout = e.getCause() instanceof java.net.SocketTimeoutException;
            throw new VerificationException(
                    readTimeout ? ErrorClass.AMBIGUOUS : ErrorClass.TRANSIENT,
                    readTimeout ? "READ_TIMEOUT" : "IO", "karza unreachable");
        }
    }
}

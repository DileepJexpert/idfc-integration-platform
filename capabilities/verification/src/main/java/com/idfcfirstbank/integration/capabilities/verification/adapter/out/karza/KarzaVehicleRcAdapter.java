package com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.TokenProviderPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Map;

/**
 * KARZA_VAHAN_RC downstream adapter (Karza, OAuth Bearer) — POSTs the MAPPED request to
 * the CONTROL-PLANE-resolved endpoint (WireMock in compose; real Karza is a URL+cred swap)
 * and returns the raw response map. Error classification drives retry/DLQ: 4xx = PERMANENT
 * (bad request, no point retrying), 5xx / I/O = TRANSIENT (retry then DLQ). NO PII logged.
 */
@Component
public class KarzaVehicleRcAdapter implements VerificationAdapter {

    private final TokenProviderPort tokens;
    private final RestClient restClient = RestClient.create();

    public KarzaVehicleRcAdapter(TokenProviderPort tokens) {
        this.tokens = tokens;
    }

    @Override
    public String svcName() {
        return "KARZA_VAHAN_RC";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(ResolvedRoute route, Map<String, Object> mappedRequest) {
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
            throw new VerificationException(ErrorClass.TRANSIENT, "IO", "karza unreachable");
        }
    }
}

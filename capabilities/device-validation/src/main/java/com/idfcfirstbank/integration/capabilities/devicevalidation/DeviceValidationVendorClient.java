package com.idfcfirstbank.integration.capabilities.devicevalidation;

import com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.BrandRow;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * REAL outbound HTTP to the device-validation vendor (the mock-vendors server in
 * the demo). This is the flow that matters — real client, real connect/read
 * timeouts, real per-brand auth, real HTTP-status → {@link ErrorClass} mapping —
 * modelled on the platform's {@code KarzaClient}. Only the vendor's response
 * DATA is mocked, on the other side of this wire.
 *
 * <p>Classification (drives the engine retry): 4xx → PERMANENT, 5xx → TRANSIENT,
 * read-timeout → AMBIGUOUS, connect/IO → TRANSIENT. Purpose-specific — NOT the
 * census-gated generic-http router.
 */
@Component
public class DeviceValidationVendorClient implements DeviceValidationVendor {

    private final DeviceValidationProperties props;
    private final RestClient http;

    public DeviceValidationVendorClient(DeviceValidationProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.connectTimeoutMs());
        rf.setReadTimeout(props.readTimeoutMs());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    /** POST {vendorBaseUrl}/{operation} with the brand's real auth header. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(String operation, String brand, String deviceId, BrandRow row) {
        if (props.vendorBaseUrl() == null) {
            throw new CapabilityException(ErrorClass.PERMANENT,
                    "device-validation.vendor-base-url not configured");
        }
        String url = props.vendorBaseUrl() + "/" + operation;
        try {
            RestClient.RequestBodySpec spec = http.post()
                    .uri(java.net.URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON);
            String auth = authHeader(row);
            if (auth != null) {
                spec = spec.header("Authorization", auth);
            }
            Map<String, Object> body = spec
                    .body(Map.of("brand", brand, "deviceId", deviceId))
                    .retrieve().body(Map.class);
            if (body == null) {
                throw new CapabilityException(ErrorClass.PERMANENT, "empty vendor response");
            }
            return body;
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError()
                    ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new CapabilityException(ec, "vendor HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            if (causedBy(e, SocketTimeoutException.class)) {
                throw new CapabilityException(ErrorClass.AMBIGUOUS, "vendor read timeout");
            }
            if (causedBy(e, IOException.class)) {
                throw new CapabilityException(ErrorClass.TRANSIENT, "vendor unreachable");
            }
            throw new CapabilityException(ErrorClass.PERMANENT, "vendor response error");
        }
    }

    /**
     * Build the real Authorization header per the brand's SCHEME:
     * NA → none; BASIC → {@code Basic base64(user:pass)}; OATH/OAUTH → fetch a
     * bearer token from the token endpoint (the real client-credentials step),
     * then {@code Bearer <token>}.
     */
    private String authHeader(BrandRow row) {
        String scheme = row.authType() == null ? "NA" : row.authType().toUpperCase();
        return switch (scheme) {
            case "BASIC", "BAUTH" -> {
                String creds = (row.basicUser() == null ? "" : row.basicUser())
                        + ":" + (row.basicPassword() == null ? "" : row.basicPassword());
                yield "Basic " + Base64.getEncoder()
                        .encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            }
            case "OATH", "OAUTH" -> "Bearer " + fetchToken(row.scope());
            default -> null; // NA — no auth
        };
    }

    @SuppressWarnings("unchecked")
    private String fetchToken(String scope) {
        if (props.tokenUrl() == null) {
            throw new CapabilityException(ErrorClass.PERMANENT,
                    "OAUTH brand but device-validation.token-url not configured");
        }
        try {
            String url = props.tokenUrl()
                    + "?grant_type=client_credentials&scope=" + (scope == null ? "" : scope);
            Map<String, Object> token = http.post()
                    .uri(java.net.URI.create(url)).retrieve().body(Map.class);
            if (token == null || token.get("access_token") == null) {
                throw new CapabilityException(ErrorClass.TRANSIENT, "token endpoint gave no token");
            }
            return String.valueOf(token.get("access_token"));
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError()
                    ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new CapabilityException(ec, "token HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new CapabilityException(ErrorClass.TRANSIENT, "token endpoint unreachable");
        }
    }

    private static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }
}

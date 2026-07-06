package com.idfcfirstbank.integration.capabilities.lmsutilities.adapter.out.http;

import com.idfcfirstbank.integration.capabilities.lmsutilities.config.LmsUtilitiesProperties;
import com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out.LmsUtilityPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.Map;

/**
 * REAL outbound HTTP to the LMS-utilities backend (the WireMock vendor in dev — only
 * the response DATA is mocked). Mandatory connect/read timeouts: the partner is
 * BLOCKING on the response, so a hung downstream must fail fast, not pin the HTTP
 * thread. A 200 body — the IDFC house envelope, whether it carries offer rows or an
 * empty {@code resource_data} — is returned RAW (the service maps it); any transport
 * failure throws {@link SyncTechnicalException} with a class so the caller/ops can
 * tell a definitely-not-processed apart from a maybe-processed or a retryable.
 */
@Component
public class LmsUtilityHttpClient implements LmsUtilityPort {

    private static final String PATH = "/api/v1/callLmsUtilities";

    private final LmsUtilitiesProperties props;
    private final RestClient http;

    public LmsUtilityHttpClient(LmsUtilitiesProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.connectTimeoutMs());
        rf.setReadTimeout(props.readTimeoutMs());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> call(Map<String, Object> requestBody) {
        if (props.vendorBaseUrl() == null) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "NOT_CONFIGURED",
                    "lms-utilities.vendor-base-url not configured");
        }
        try {
            RestClient.RequestBodySpec spec = http.post()
                    .uri(URI.create(props.vendorBaseUrl() + PATH))
                    .contentType(MediaType.APPLICATION_JSON);
            if (props.vendorAuthToken() != null) {
                spec = spec.header("Authorization", "Bearer " + props.vendorAuthToken());
            }
            Map<String, Object> body = spec.body(requestBody).retrieve().body(Map.class);
            if (body == null) {
                throw new SyncTechnicalException(ErrorClass.PERMANENT, "EMPTY_RESPONSE",
                        "LMS backend returned an empty body");
            }
            return body;   // the RAW house envelope — the service normalizes it, not this adapter
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new SyncTechnicalException(ec, "HTTP_" + e.getStatusCode().value(), "LMS backend http error");
        } catch (RestClientException e) {
            if (causedBy(e, SocketTimeoutException.class)) {
                // A read timeout is AMBIGUOUS on the sync lane — the request may have been processed.
                throw new SyncTechnicalException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "LMS backend read timeout");
            }
            if (causedBy(e, IOException.class)) {
                throw new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "LMS backend unreachable");
            }
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "RESPONSE", "LMS backend response error");
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

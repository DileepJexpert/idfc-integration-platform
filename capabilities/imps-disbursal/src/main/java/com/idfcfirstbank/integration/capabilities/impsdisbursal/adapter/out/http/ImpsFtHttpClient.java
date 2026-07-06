package com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.out.http;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.config.ImpsDisbursalProperties;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.error.SyncTechnicalException;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtRequest;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsFtPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
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
 * REAL outbound HTTP to the IMPS/FT backend (the mock-imps WireMock in dev — only
 * the response DATA is mocked). Mandatory connect/read timeouts: the partner is
 * BLOCKING on the response, so a hung downstream must fail fast, not pin the HTTP
 * thread. A 200 body (success OR a business decline) is returned as an
 * {@link ImpsFtResult}; any transport failure throws {@link SyncTechnicalException}
 * with a class — crucially a read timeout on a money movement is AMBIGUOUS (the
 * transfer may have gone through), never a fake success.
 */
@Component
public class ImpsFtHttpClient implements ImpsFtPort {

    private static final String PATH = "/api/v1/impsFT";

    private final ImpsDisbursalProperties props;
    private final RestClient http;

    public ImpsFtHttpClient(ImpsDisbursalProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(props.connectTimeoutMs());
        rf.setReadTimeout(props.readTimeoutMs());
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public ImpsFtResult transfer(ImpsFtRequest request) {
        if (props.vendorBaseUrl() == null) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "NOT_CONFIGURED",
                    "imps-disbursal.vendor-base-url not configured");
        }
        try {
            RestClient.RequestBodySpec spec = http.post()
                    .uri(URI.create(props.vendorBaseUrl() + PATH))
                    .contentType(MediaType.APPLICATION_JSON);
            if (props.vendorAuthToken() != null) {
                spec = spec.header("Authorization", "Bearer " + props.vendorAuthToken());
            }
            Map<String, Object> body = spec.body(request.toVendorBody()).retrieve().body(Map.class);
            if (body == null) {
                throw new SyncTechnicalException(ErrorClass.PERMANENT, "EMPTY_RESPONSE",
                        "IMPS backend returned an empty body");
            }
            return ImpsFtResult.fromVendorBody(body);
        } catch (RestClientResponseException e) {
            ErrorClass ec = e.getStatusCode().is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new SyncTechnicalException(ec, "HTTP_" + e.getStatusCode().value(), "IMPS backend http error");
        } catch (RestClientException e) {
            if (causedBy(e, SocketTimeoutException.class)) {
                // A read timeout on a fund transfer is AMBIGUOUS — the money MAY have moved.
                throw new SyncTechnicalException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "IMPS backend read timeout");
            }
            if (causedBy(e, IOException.class)) {
                throw new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "IMPS backend unreachable");
            }
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "RESPONSE", "IMPS backend response error");
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

package com.idfcfirstbank.integration.demo.fusionhcm;

import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.beans.factory.annotation.Value;
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
 * REAL outbound HTTP to Oracle Fusion HCM (the mock-vendors server in the demo).
 * Real client, real timeouts, real HTTP-status → {@link ErrorClass} mapping
 * (4xx → PERMANENT, 5xx → TRANSIENT, read-timeout → AMBIGUOUS, connect/IO →
 * TRANSIENT). Only the response DATA is mocked, on the other side of the wire.
 */
@Component
public class FusionVendorClient implements FusionVendor {

    private final String baseUrl;
    private final RestClient http;

    public FusionVendorClient(
            @Value("${demo.fusion.base-url:http://localhost:9107/vendor/fusion}") String baseUrl,
            @Value("${demo.fusion.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${demo.fusion.read-timeout-ms:10000}") int readTimeoutMs) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(connectTimeoutMs);
        rf.setReadTimeout(readTimeoutMs);
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateEmployee(String employeeId, String lastWorkingDay) {
        try {
            Map<String, Object> body = http.post()
                    .uri(URI.create(baseUrl + "/employees/" + employeeId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("lastWorkingDay", lastWorkingDay == null ? "" : lastWorkingDay))
                    .retrieve().body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (RestClientException e) {
            throw transport(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getEmployee(String employeeId) {
        try {
            Map<String, Object> body = http.get()
                    .uri(URI.create(baseUrl + "/employees/" + employeeId))
                    .retrieve().body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientResponseException e) {
            throw classify(e);
        } catch (RestClientException e) {
            throw transport(e);
        }
    }

    private static CapabilityException classify(RestClientResponseException e) {
        ErrorClass ec = e.getStatusCode().is4xxClientError()
                ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
        return new CapabilityException(ec, "fusion HTTP " + e.getStatusCode().value());
    }

    private static CapabilityException transport(RestClientException e) {
        if (causedBy(e, SocketTimeoutException.class)) {
            return new CapabilityException(ErrorClass.AMBIGUOUS, "fusion read timeout");
        }
        if (causedBy(e, IOException.class)) {
            return new CapabilityException(ErrorClass.TRANSIENT, "fusion unreachable");
        }
        return new CapabilityException(ErrorClass.PERMANENT, "fusion response error");
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

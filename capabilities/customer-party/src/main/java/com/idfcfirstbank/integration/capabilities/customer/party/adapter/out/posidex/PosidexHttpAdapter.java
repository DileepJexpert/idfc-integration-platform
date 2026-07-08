package com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Map;

/**
 * Real Posidex adapter — HTTP POST the applicant identity to the Posidex resolve
 * endpoint (base URL from config: the docker mock in compose, the real Posidex in
 * prod, no code change). Active when {@code idfc.customer-party.posidex.mode=real}.
 *
 * <p>Transport/HTTP failures are CLASSIFIED into a {@link CapabilityException}
 * carrying an {@link ErrorClass} (same discipline as KarzaClient/LmsUtilityHttpClient)
 * so the engine's retry policy sees the truth: a 4xx is PERMANENT (DLQ), a 5xx or a
 * connect/IO failure (the vendor is simply down) is TRANSIENT (retry). resolve is an
 * idempotent READ, so a read timeout is AMBIGUOUS but still safe to retry.
 */
public class PosidexHttpAdapter implements PosidexPort {

    private final RestClient restClient;

    public PosidexHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CustomerProfile resolve(Map<String, Object> identity) {
        Map<String, Object> body;
        try {
            body = restClient.post()
                    .uri("/posidex/resolve")
                    .body(identity)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException e) {
            // 4xx (malformed / auth) will not fix on retry -> PERMANENT (DLQ);
            // 5xx (server-side blip) -> TRANSIENT (retry with backoff).
            ErrorClass ec = e.getStatusCode().is4xxClientError() ? ErrorClass.PERMANENT : ErrorClass.TRANSIENT;
            throw new CapabilityException(ec, "posidex HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            // Transport-level. resolve is an idempotent READ, so retrying is safe: a read
            // timeout (post-send) is AMBIGUOUS; a connect-refused / IO failure means the
            // vendor/mock is simply down -> TRANSIENT (retry), NOT a hard PERMANENT.
            if (causedBy(e, SocketTimeoutException.class)) {
                throw new CapabilityException(ErrorClass.AMBIGUOUS, "posidex read timeout", e);
            }
            if (causedBy(e, IOException.class)) {
                throw new CapabilityException(ErrorClass.TRANSIENT, "posidex unreachable", e);
            }
            throw new CapabilityException(ErrorClass.PERMANENT, "posidex response error", e);
        }
        if (body == null) {
            throw new CapabilityException(ErrorClass.PERMANENT, "posidex returned empty body");
        }
        return new CustomerProfile(
                str(body.get("crn")), str(body.get("customerId")),
                str(body.get("name")), str(body.getOrDefault("status", "ACTIVE")));
    }

    private static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

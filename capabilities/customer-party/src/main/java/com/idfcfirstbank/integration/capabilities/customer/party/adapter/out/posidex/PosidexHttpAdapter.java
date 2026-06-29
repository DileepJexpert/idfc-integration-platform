package com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real Posidex adapter — HTTP POST the applicant identity to the Posidex resolve
 * endpoint (base URL from config: the docker mock in compose, the real Posidex in
 * prod, no code change). Active when {@code idfc.customer-party.posidex.mode=real}.
 */
public class PosidexHttpAdapter implements PosidexPort {

    private final RestClient restClient;

    public PosidexHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CustomerProfile resolve(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post()
                .uri("/posidex/resolve")
                .body(identity)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("posidex returned empty body");
        }
        return new CustomerProfile(
                str(body.get("crn")), str(body.get("customerId")),
                str(body.get("name")), str(body.getOrDefault("status", "ACTIVE")));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

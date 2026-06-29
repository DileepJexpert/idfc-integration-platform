package com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico;

import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real FICO adapter — HTTP POST the applicant payload to the FICO score endpoint
 * (base URL from config: the docker mock in compose, the real FICO in prod, no
 * code change). Active when {@code idfc.scoring.fico-mode=real}.
 */
public class FicoHttpAdapter implements FicoPort {

    private final RestClient restClient;

    public FicoHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public int score(Map<String, Object> payload) {
        Map<String, Object> body = restClient.post()
                .uri("/fico/score")
                .body(payload)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("fico returned empty body");
        }
        return ((Number) body.get("score")).intValue();
    }
}

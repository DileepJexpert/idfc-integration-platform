package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReport;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilPort;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real CIBIL adapter — HTTP POST the applicant identity to the CIBIL report
 * endpoint (base URL from config: the docker mock in compose, the real CIBIL in
 * prod, no code change). Active when {@code idfc.bureau.cibil.mode=real}.
 */
public class CibilHttpAdapter implements CibilPort {

    private final RestClient restClient;

    public CibilHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public BureauReport fetch(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post()
                .uri("/cibil/report")
                .body(identity)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("cibil returned empty body");
        }
        // TODO: parity harness (post-demo)
        return new BureauReport(
                ((Number) body.get("score")).intValue(),
                str(body.get("grade")),
                str(body.get("reportId")));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

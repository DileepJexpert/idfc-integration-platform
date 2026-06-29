package com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl;

import com.idfcfirstbank.integration.capabilities.kyc.domain.model.KycResult;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real NSDL adapter — HTTP POST the applicant identity to the NSDL verify
 * endpoint (base URL from config: the docker mock in compose, the real NSDL in
 * prod, no code change). Active when {@code idfc.kyc.nsdl.mode=real}.
 */
public class NsdlHttpAdapter implements NsdlPort {

    private final RestClient restClient;

    public NsdlHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public KycResult verify(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post()
                .uri("/nsdl/verify")
                .body(identity)
                .retrieve()
                .body(Map.class);
        if (body == null) {
            throw new IllegalStateException("nsdl returned empty body");
        }
        return new KycResult(
                str(body.getOrDefault("status", "VERIFIED")), str(body.get("kycRefId")));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

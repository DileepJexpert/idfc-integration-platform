package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilBureauPort;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Real CIBIL adapter — HTTP POST the identity to the CIBIL endpoint (URL via
 * config: docker mock in compose, real CIBIL in prod, no code change) and
 * translate the vendor's shape into the canonical result. Active when
 * {@code idfc.bureau.cibil.mode=real}.
 *
 * <p>// TODO: parity harness (post-demo) — record real-vs-canonical for cutover.
 */
public class CibilHttpAdapter implements CibilBureauPort {

    private final RestClient restClient;

    public CibilHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public BureauType type() {
        return BureauType.CIBIL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post().uri("/cibil/report").body(identity).retrieve().body(Map.class);
        if (body == null) {
            throw new IllegalStateException("CIBIL returned an empty body");
        }
        return new CanonicalBureauResult(BureauType.CIBIL,
                ((Number) body.getOrDefault("score", 0)).intValue(),
                str(body.get("grade")), str(body.get("reportId")),
                "cibil", Instant.now().toString(), body);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

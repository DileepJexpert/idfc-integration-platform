package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CommercialBureauPort;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/** Real Commercial Bureau adapter — HTTP POST {@code /commercial/report}; URL via config. */
public class CommercialBureauHttpAdapter implements CommercialBureauPort {

    private final RestClient restClient;

    public CommercialBureauHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public BureauType type() {
        return BureauType.COMMERCIAL;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post().uri("/commercial/report").body(identity).retrieve().body(Map.class);
        if (body == null) {
            throw new IllegalStateException("Commercial Bureau returned an empty body");
        }
        return new CanonicalBureauResult(BureauType.COMMERCIAL,
                ((Number) body.getOrDefault("score", 0)).intValue(),
                str(body.get("grade")), str(body.get("reportId")),
                "commercial", Instant.now().toString(), body);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

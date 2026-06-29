package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.MultiBureauPort;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/** Real Multi-Bureau adapter — HTTP POST {@code /multibureau/report}; URL via config. */
public class MultiBureauHttpAdapter implements MultiBureauPort {

    private final RestClient restClient;

    public MultiBureauHttpAdapter(String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public BureauType type() {
        return BureauType.MULTI_BUREAU;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        Map<String, Object> body = restClient.post().uri("/multibureau/report").body(identity).retrieve().body(Map.class);
        if (body == null) {
            throw new IllegalStateException("Multi-Bureau returned an empty body");
        }
        return new CanonicalBureauResult(BureauType.MULTI_BUREAU,
                ((Number) body.getOrDefault("score", 0)).intValue(),
                str(body.get("grade")), str(body.get("reportId")),
                "multibureau", Instant.now().toString(), body);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.MultiBureauPort;

import java.time.Instant;
import java.util.Map;

/** Local mock Multi-Bureau — deterministic; mirrors CIBIL's high/low heuristic. */
public class MockMultiBureauAdapter implements MultiBureauPort {

    @Override
    public BureauType type() {
        return BureauType.MULTI_BUREAU;
    }

    @Override
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String applicationRef = String.valueOf(identity.getOrDefault("applicationRef", ""));
        boolean low = (pan + " " + applicationRef).toUpperCase().contains("LOW");
        int score = low ? 530 : 770;
        return new CanonicalBureauResult(BureauType.MULTI_BUREAU, score, low ? "C" : "A",
                "MB-" + pan, "mock-multibureau", Instant.now().toString(), Map.of("bureaus", 3));
    }
}

package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CommercialBureauPort;

import java.time.Instant;
import java.util.Map;

/** Local mock Commercial Bureau — for business/commercial applicants. */
public class MockCommercialBureauAdapter implements CommercialBureauPort {

    @Override
    public BureauType type() {
        return BureauType.COMMERCIAL;
    }

    @Override
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String applicationRef = String.valueOf(identity.getOrDefault("applicationRef", ""));
        boolean low = (pan + " " + applicationRef).toUpperCase().contains("LOW");
        int score = low ? 520 : 760;
        return new CanonicalBureauResult(BureauType.COMMERCIAL, score, low ? "C" : "B",
                "COM-" + pan, "mock-commercial", Instant.now().toString(), Map.of("entityType", "PVT_LTD"));
    }
}

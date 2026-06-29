package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilBureauPort;

import java.time.Instant;
import java.util.Map;

/**
 * Local mock CIBIL — deterministic so the bureau branch is demoable BOTH ways
 * without a docker vendor. A "LOW" marker in the PAN or the applicationRef yields
 * a low/declinable profile (applicationRef is used too because in the live edge
 * path the PAN travels via the S3 claim-check). Used for tests and mock mode.
 */
public class MockCibilAdapter implements CibilBureauPort {

    @Override
    public BureauType type() {
        return BureauType.CIBIL;
    }

    @Override
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String applicationRef = String.valueOf(identity.getOrDefault("applicationRef", ""));
        boolean low = (pan + " " + applicationRef).toUpperCase().contains("LOW");
        int score = low ? 540 : 780;
        return new CanonicalBureauResult(BureauType.CIBIL, score, low ? "C" : "A", "CIBIL-" + pan,
                "mock-cibil", Instant.now().toString(), Map.of("enquiries", low ? 7 : 1));
    }
}

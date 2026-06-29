package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.scorecard;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.ScorecardInfraPort;

import java.time.Instant;
import java.util.Map;

/**
 * Internal scorecard infrastructure (scorecard.dev-infinity already partially
 * fronts the bureaus today; this formalizes it as a backing source). Mock only —
 * it is internal, not an external vendor URL.
 */
public class MockScorecardInfraAdapter implements ScorecardInfraPort {

    @Override
    public BureauType type() {
        return BureauType.SCORECARD_INFRA;
    }

    @Override
    public CanonicalBureauResult fetch(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String applicationRef = String.valueOf(identity.getOrDefault("applicationRef", ""));
        boolean low = (pan + " " + applicationRef).toUpperCase().contains("LOW");
        int score = low ? 545 : 790;
        return new CanonicalBureauResult(BureauType.SCORECARD_INFRA, score, low ? "C" : "A",
                "SCI-" + pan, "scorecard-infra", Instant.now().toString(), Map.of("model", "infinity-v2"));
    }
}

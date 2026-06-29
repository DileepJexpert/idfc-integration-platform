package com.idfcfirstbank.integration.capabilities.scoring.application;

import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.MockFicoAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.domain.model.ScoringDecision;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;
import com.idfcfirstbank.integration.capabilities.scoring.domain.service.DecisionRule;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScoringServiceTest {

    private static final int THRESHOLD = 700;

    private ScoringService service() {
        return new ScoringService(new MockFicoAdapter(), new DecisionRule(), THRESHOLD);
    }

    private CapabilityRequest request(Map<String, Object> payload, Map<String, Object> collected) {
        return new CapabilityRequest("ji-1", "corr-1", "scoring", "n_scoring", payload, collected);
    }

    @Test
    void approvesWhenBureauScoreMeetsThreshold() {
        CapabilityRequest req = request(
                Map.of(),
                Map.of("bureau", Map.of("bureauScore", 780)));

        CapabilityResponse resp = service().handle(req);

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.capabilityKey()).isEqualTo("scoring");
        assertThat(resp.nodeId()).isEqualTo("n_scoring");
        assertThat(resp.result()).containsEntry("decision", ScoringDecision.APPROVED);
        assertThat(resp.result()).containsEntry("score", 780);
    }

    @Test
    void rejectsWhenBureauScoreBelowThreshold() {
        CapabilityRequest req = request(
                Map.of(),
                Map.of("bureau", Map.of("bureauScore", 540)));

        CapabilityResponse resp = service().handle(req);

        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.result()).containsEntry("decision", ScoringDecision.REJECTED);
        assertThat(resp.result()).containsEntry("score", 540);
    }

    @Test
    void enrichesReasonsWithFicoScore() {
        CapabilityRequest req = request(
                Map.of(),
                Map.of("bureau", Map.of("bureauScore", 780)));

        CapabilityResponse resp = service().handle(req);

        Object reasons = resp.result().get("reasons");
        assertThat(reasons.toString()).contains("fico=750");
    }

    @Test
    void missingBureauResultScoresZeroAndRejects() {
        CapabilityResponse resp = service().handle(request(Map.of(), Map.of()));

        assertThat(resp.result()).containsEntry("decision", ScoringDecision.REJECTED);
        assertThat(resp.result()).containsEntry("score", 0);
    }

    @Test
    void ficoFailureYieldsErrorResponse() {
        FicoPort failing = payload -> {
            throw new RuntimeException("fico down");
        };
        ScoringService service = new ScoringService(failing, new DecisionRule(), THRESHOLD);
        CapabilityResponse resp = service.handle(request(Map.of(), Map.of("bureau", Map.of("bureauScore", 780))));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
    }
}

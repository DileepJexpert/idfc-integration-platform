package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coordinator test with fake ports and a simulated capability fleet —
 * the full edge->engine->capabilities->decision flow with NO Kafka. The fake
 * fleet mimics each real capability's behaviour (bureau score, scoring decision,
 * booking loanId) so the branch is exercised both ways.
 */
class JourneyOrchestratorFlowTest {

    private final JourneyDefinition def =
            new JourneyDefinitionLoader(new ObjectMapper()).loadFromClasspath("journeys/loan-origination.journey.json");

    /** Captures published capability requests as a FIFO queue. */
    private static final class QueuePort implements CapabilityRequestPort {
        final Deque<CapabilityRequest> queue = new ArrayDeque<>();
        public void publish(CapabilityRequest r) { queue.add(r); }
    }

    private static final class CapturingDecisionPort implements DecisionOutboundPort {
        final List<JourneyDecision> decisions = new ArrayList<>();
        public void publish(JourneyDecision d) { decisions.add(d); }
    }

    @Test
    void highScoreApplicantIsApprovedAndBooked() {
        JourneyDecision decision = runFlow(800);
        assertThat(decision.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(decision.loanId()).isNotBlank();
    }

    @Test
    void lowScoreApplicantIsRejectedAndNotBooked() {
        JourneyDecision decision = runFlow(500);
        assertThat(decision.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(decision.loanId()).isNull();
    }

    /** Run the whole journey, driving a simulated capability fleet, return the final decision. */
    private JourneyDecision runFlow(int bureauScore) {
        JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        JourneyRegistry registry = new JourneyRegistry(List.of(def), Map.of());
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        QueuePort requests = new QueuePort();
        CapturingDecisionPort decisions = new CapturingDecisionPort();

        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                engine, registry, store, requests, decisions, () -> "ji-fixed");

        orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-a", "applicationRef", "APP-1",
                "payload", Map.of("pan", "ABCDE1234F")));

        int guard = 0;
        while (decisions.decisions.isEmpty() && guard++ < 25) {
            CapabilityRequest req = requests.queue.poll();
            assertThat(req).as("flow stalled with no pending capability request").isNotNull();
            orchestrator.onCapabilityResponse(new CapabilityResponse(
                    req.journeyInstanceId(), req.correlationId(), req.nodeId(), req.capabilityKey(),
                    CapabilityStatus.OK, cannedResult(req, bureauScore)));
        }

        assertThat(decisions.decisions).hasSize(1);
        return decisions.decisions.get(0);
    }

    /** A stand-in for the real capability fleet's outputs. */
    private Map<String, Object> cannedResult(CapabilityRequest req, int bureauScore) {
        return switch (req.capabilityKey()) {
            case "customer-party" -> Map.of("crn", "CRN-1");
            case "kyc" -> Map.of("kycStatus", "VERIFIED");
            case "bureau" -> Map.of("bureauScore", bureauScore);
            case "scoring" -> {
                // read the bureau score out of collectedResults the way scoring will
                Object bureau = req.collectedResults().get("bureau");
                int bs = bureau instanceof Map<?, ?> m && m.get("bureauScore") instanceof Number num
                        ? num.intValue() : 0;
                yield Map.of("decision", bs >= 700 ? "APPROVED" : "REJECTED", "score", bs);
            }
            case "lending-origination" -> Map.of("loanId", "LN-900", "status", "BOOKED");
            default -> Map.of();
        };
    }
}

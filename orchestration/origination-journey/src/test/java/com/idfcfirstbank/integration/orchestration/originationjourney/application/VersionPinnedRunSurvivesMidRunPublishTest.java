package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE A2 hard requirement, as a named regression: a run started on v1 completes
 * on v1 even after v2 publishes mid-run. v2 deliberately CHANGES the graph after
 * the first task (adds a second task before the terminal), so any resume that
 * wrongly resolved "current" instead of the pinned version would visibly dispatch
 * the v2-only node instead of finishing.
 */
class VersionPinnedRunSurvivesMidRunPublishTest {

    private static final String KEY = "pin-journey";

    /** v1: verify -> done. */
    private static JourneyDefinition v1() {
        JourneyNode verify = JourneyNode.task("n_verify", null, "kyc", "verify", null,
                "context.kyc", null, null, null, false, List.of("n_done"));
        JourneyNode done = JourneyNode.terminal("n_done", "push_decision_to_channel",
                List.of(), "completed");
        return new JourneyDefinition(KEY, 1, "n_verify", List.of(verify, done));
    }

    /** v2: verify -> EXTRA SCORING TASK -> done. A v2 resume would dispatch n_extra. */
    private static JourneyDefinition v2() {
        JourneyNode verify = JourneyNode.task("n_verify", null, "kyc", "verify", null,
                "context.kyc", null, null, null, false, List.of("n_extra"));
        JourneyNode extra = JourneyNode.task("n_extra", null, "scoring", "decide", null,
                "context.scoring", null, null, null, false, List.of("n_done"));
        JourneyNode done = JourneyNode.terminal("n_done", "push_decision_to_channel",
                List.of(), "completed");
        return new JourneyDefinition(KEY, 2, "n_verify", List.of(verify, extra, done));
    }

    @Test
    void aV2PublishMidRunDoesNotChangeARunningV1Journey() {
        FixedJourneySource source = new FixedJourneySource(List.of(v1()));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of("PERSONAL_LOAN", KEY));
        registry.bootstrap();

        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        List<CapabilityRequest> requests = new CopyOnWriteArrayList<>();
        List<JourneyDecision> decisions = new CopyOnWriteArrayList<>();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()), registry, store,
                requests::add, decisions::add, () -> "ji-random");

        // The run starts while v1 is current — and PINS v1.
        String runId = orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-pin-1", "applicationRef", "APP-PIN"));
        assertThat(store.find(runId).orElseThrow().journeyVersion()).isEqualTo(1);
        assertThat(requests).extracting(CapabilityRequest::nodeId).containsExactly("n_verify");

        // Mid-run, a checker publishes v2 and the engine's refresh picks it up.
        source.replaceCurrent(List.of(v2()));
        registry.refresh();

        // A NEW run starts on v2 (the publish did its job for new business)...
        String newRunId = orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-pin-2", "applicationRef", "APP-NEW"));
        assertThat(store.find(newRunId).orElseThrow().journeyVersion()).isEqualTo(2);

        // ...but the IN-FLIGHT run resumes on its PINNED v1: completing n_verify
        // finishes the journey. No v2-only n_extra dispatch, a completed decision.
        requests.clear();
        orchestrator.onCapabilityResponse(new CapabilityResponse(
                runId, "corr-pin-1", "n_verify", "kyc", CapabilityStatus.OK, Map.of("kycOk", true)));

        assertThat(requests)
                .as("a pinned v1 run must never dispatch the v2-only node")
                .extracting(CapabilityRequest::nodeId)
                .doesNotContain("n_extra");
        assertThat(decisions)
                .as("the v1 run completed on the v1 graph")
                .singleElement()
                .extracting(JourneyDecision::journeyInstanceId)
                .isEqualTo(runId);
        assertThat(store.find(runId).orElseThrow().journeyVersion())
                .as("the pin is immutable run state")
                .isEqualTo(1);
    }
}

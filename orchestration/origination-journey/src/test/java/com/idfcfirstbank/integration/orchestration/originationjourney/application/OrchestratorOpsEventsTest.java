package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.scheduler.JourneyLivenessSweeper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.OpsEvent;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.OpsEventPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.2: the run-lifecycle event stream — every hop leaves its event, run
 * terminals carry the outcome, and the sweeper stamps sweptTimeout. Events are
 * ids only; content is asserted by shape elsewhere (the record has no payload
 * field to leak).
 */
class OrchestratorOpsEventsTest {

    private static JourneyDefinition journey() {
        JourneyNode verify = JourneyNode.task("n_verify", null, "kyc", "verify", null,
                "context.kyc", null, null, null, false, List.of("n_done"));
        JourneyNode done = JourneyNode.terminal("n_done", "push_decision_to_channel",
                List.of(), "completed");
        return new JourneyDefinition("ops-journey", 1, "n_verify", List.of(verify, done));
    }

    private final List<OpsEvent> events = new CopyOnWriteArrayList<>();
    private final InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
    private final JourneyOrchestrator orchestrator = new JourneyOrchestrator(
            new JourneyEngine(new ExpressionEvaluator()),
            FixedJourneySource.registry(Map.of("OPS", "ops-journey"), journey()),
            store, r -> { }, d -> { }, () -> "ji-random", events::add);

    private String start(String corr) {
        return orchestrator.onOrigination(Map.of(
                "type", "OPS", "correlationId", corr, "applicationRef", "APP-OPS"));
    }

    @Test
    void aHappyRunEmitsStartedDispatchedCompletedAndRunCompletedWithOutcome() {
        String runId = start("corr-ok");
        orchestrator.onCapabilityResponse(new CapabilityResponse(
                runId, "corr-ok", "n_verify", "kyc", CapabilityStatus.OK, Map.of("ok", true)));

        assertThat(events).extracting(OpsEvent::event).containsExactly(
                OpsEvent.RUN_STARTED, OpsEvent.NODE_DISPATCHED,
                OpsEvent.NODE_COMPLETED, OpsEvent.RUN_COMPLETED);
        OpsEvent ended = events.get(3);
        assertThat(ended.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(ended.journeyInstanceId()).isEqualTo(runId);
        assertThat(ended.journeyVersion()).isEqualTo(1);
        assertThat(events.get(1).nodeId()).isEqualTo("n_verify");
    }

    @Test
    void anErrorResponseEmitsNodeFailedAndRunFailed() {
        String runId = start("corr-err");
        orchestrator.onCapabilityResponse(new CapabilityResponse(
                runId, "corr-err", "n_verify", "kyc", CapabilityStatus.ERROR,
                Map.of(), ErrorClass.PERMANENT));

        assertThat(events).extracting(OpsEvent::event).containsExactly(
                OpsEvent.RUN_STARTED, OpsEvent.NODE_DISPATCHED,
                OpsEvent.NODE_FAILED, OpsEvent.RUN_FAILED);
        assertThat(store.find(runId).orElseThrow().status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(store.find(runId).orElseThrow().terminalNodeId()).isEqualTo("n_verify");
    }

    @Test
    void theSweeperEmitsSweptTimeoutAndStampsNotified() {
        String runId = start("corr-stuck");
        events.clear();

        Clock future = Clock.fixed(Instant.now().plus(Duration.ofHours(2)), ZoneOffset.UTC);
        JourneyLivenessSweeper sweeper = new JourneyLivenessSweeper(
                store, d -> { }, events::add, future, 900);
        assertThat(sweeper.sweepStuckRuns()).isEqualTo(1);

        assertThat(events).extracting(OpsEvent::event).containsExactly(OpsEvent.RUN_SWEPT_TIMEOUT);
        JourneyInstance swept = store.find(runId).orElseThrow();
        assertThat(swept.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(swept.sfdcNotified())
                .as("the sweeper notified the channel BEFORE failing the run")
                .isEqualTo(JourneyInstance.NotifyState.SENT);
        assertThat(swept.terminalNodeId()).isEqualTo("__timeout__");
        assertThat(swept.endedAt()).isNotNull();
    }
}

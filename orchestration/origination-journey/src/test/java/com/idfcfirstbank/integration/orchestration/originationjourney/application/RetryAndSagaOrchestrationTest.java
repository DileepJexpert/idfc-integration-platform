package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 retry + saga through the ORCHESTRATOR (persist-before-publish intact):
 * a scheduled retry parks its node id in the durable outbox and the delayed
 * re-drive publishes the attempt-suffixed request; compensation responses flow
 * through the duplicate guard while the run is COMPENSATING.
 */
class RetryAndSagaOrchestrationTest {

    private static final String RETRY_JOURNEY = """
            {"journeyKey":"pl-retry","startNodeId":"a",
             "nodes":[
               {"id":"a","type":"task","capability":"flaky",
                "policies":{"retry":{"maxAttempts":3,
                  "backoff":{"type":"exponential","base":"PT1S","max":"PT8S"},
                  "retryOn":["TRANSIENT"]}},
                "next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    private static final String SAGA_JOURNEY = """
            {"journeyKey":"pl-saga","startNodeId":"t1",
             "nodes":[
               {"id":"t1","type":"task","capability":"capOne",
                "compensation":{"operation":"undoOne"},"next":["t2"]},
               {"id":"t2","type":"task","capability":"capTwo","onFailure":"compensate",
                "next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    private static final class QueuePort implements CapabilityRequestPort {
        final List<CapabilityRequest> published = new ArrayList<>();
        public void publish(CapabilityRequest r) { published.add(r); }
        CapabilityRequest last() { return published.get(published.size() - 1); }
    }

    private static final class CapturingDecisionPort implements DecisionOutboundPort {
        final List<JourneyDecision> decisions = new ArrayList<>();
        public void publish(JourneyDecision d) { decisions.add(d); }
    }

    /** Captures scheduled retry tasks so the test fires them deterministically. */
    private static final class ManualRetryScheduler implements JourneyOrchestrator.RetryScheduler {
        record Scheduled(Runnable task, long delayMillis) { }
        final List<Scheduled> scheduled = new ArrayList<>();
        public void schedule(Runnable task, long delayMillis) {
            scheduled.add(new Scheduled(task, delayMillis));
        }
        void fireAll() {
            List<Scheduled> batch = new ArrayList<>(scheduled);
            scheduled.clear();
            batch.forEach(s -> s.task().run());
        }
    }

    private static JourneyDefinition parse(String json) {
        try {
            return new JourneyDefinitionLoader(new ObjectMapper())
                    .parse(new ObjectMapper().readTree(json));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Fixture(JourneyOrchestrator orchestrator, InMemoryJourneyInstanceStore store,
                           QueuePort requests, CapturingDecisionPort decisions,
                           ManualRetryScheduler scheduler) {
    }

    private static Fixture fixture(JourneyDefinition def, String type) {
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        QueuePort requests = new QueuePort();
        CapturingDecisionPort decisions = new CapturingDecisionPort();
        ManualRetryScheduler scheduler = new ManualRetryScheduler();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                FixedJourneySource.registry(Map.of(type, def.key()), def),
                store, requests, decisions, () -> "ji-fixed",
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.OpsEventPort.NOOP,
                scheduler);
        return new Fixture(orchestrator, store, requests, decisions, scheduler);
    }

    @Test
    void aScheduledRetryIsDurableInTheOutboxAndRedrivesWithTheAttemptKey() {
        Fixture f = fixture(parse(RETRY_JOURNEY), "PL");
        String instanceId = f.orchestrator().onOrigination(Map.of(
                "type", "PL", "correlationId", "corr-r1", "payload", Map.of()));

        assertThat(f.requests().published).hasSize(1);
        assertThat(f.requests().last().idempotencyKey()).isEqualTo(instanceId + ":a");

        // TRANSIENT failure -> retry scheduled, intent persisted in the outbox.
        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-r1", "a", "flaky", CapabilityStatus.ERROR, Map.of(),
                ErrorClass.TRANSIENT));

        assertThat(f.scheduler().scheduled).singleElement()
                .satisfies(s -> assertThat(s.delayMillis()).isEqualTo(1000));
        JourneyInstance persisted = f.store().find(instanceId).orElseThrow();
        assertThat(persisted.pendingRequestNodeIds())
                .as("the retry intent is DURABLE before any timer fires").containsExactly("a");
        assertThat(persisted.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(f.requests().published).as("nothing publishes until the delay elapses").hasSize(1);

        // The delayed re-drive publishes the ATTEMPT-SUFFIXED request and clears the outbox.
        f.scheduler().fireAll();
        assertThat(f.requests().published).hasSize(2);
        assertThat(f.requests().last().idempotencyKey())
                .as("attempt 2 must NOT be deduplicated as a replay of attempt 1")
                .isEqualTo(instanceId + ":a:a2");
        assertThat(f.store().find(instanceId).orElseThrow().pendingRequestNodeIds()).isEmpty();

        // Attempt 2 succeeds -> the run completes.
        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-r1", "a", "flaky", CapabilityStatus.OK, Map.of()));
        assertThat(f.decisions().decisions).singleElement()
                .satisfies(d -> assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED));
    }

    @Test
    void sagaResponsesFlowThroughTheGuardWhileCompensating() {
        Fixture f = fixture(parse(SAGA_JOURNEY), "PL");
        String instanceId = f.orchestrator().onOrigination(Map.of(
                "type", "PL", "correlationId", "corr-s1", "payload", Map.of()));

        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-s1", "t1", "capOne", CapabilityStatus.OK, Map.of("id", "A")));
        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-s1", "t2", "capTwo", CapabilityStatus.ERROR, Map.of(),
                ErrorClass.PERMANENT));

        // Decision went out with the saga start; the comp request is in flight.
        assertThat(f.decisions().decisions).singleElement()
                .satisfies(d -> assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR));
        assertThat(f.store().find(instanceId).orElseThrow().status())
                .isEqualTo(InstanceStatus.COMPENSATING);
        assertThat(f.requests().last().nodeId()).isEqualTo("t1" + JourneyEngine.COMP_SUFFIX);

        // The comp response must be PROCESSED (not dropped by the guard).
        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-s1", "t1" + JourneyEngine.COMP_SUFFIX, "capOne",
                CapabilityStatus.OK, Map.of()));
        JourneyInstance done = f.store().find(instanceId).orElseThrow();
        assertThat(done.status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(done.terminalNodeId()).isEqualTo("t2");
        assertThat(f.decisions().decisions).as("no second decision at saga end").hasSize(1);

        // A REDELIVERED comp response is a duplicate: dropped, still one decision.
        f.orchestrator().onCapabilityResponse(new CapabilityResponse(
                instanceId, "corr-s1", "t1" + JourneyEngine.COMP_SUFFIX, "capOne",
                CapabilityStatus.OK, Map.of()));
        assertThat(f.decisions().decisions).hasSize(1);
        assertThat(f.store().find(instanceId).orElseThrow().status()).isEqualTo(InstanceStatus.FAILED);
    }
}

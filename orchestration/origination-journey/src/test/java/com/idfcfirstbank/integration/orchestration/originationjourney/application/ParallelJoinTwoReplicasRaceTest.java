package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE replicas:2 money-path race (deploy/eks ships the engine with 2 replicas).
 *
 * <p>Scenario: a parallel fan-out (t_a ‖ t_b) joined by an allOf join. The two
 * arms' capability responses land on different partitions and are processed
 * CONCURRENTLY by two engine replicas. Each replica does find → engine-advance →
 * save against the shared journey-instance store.
 *
 * <p>Without concurrency control on {@code save}, the slower writer overwrites
 * the faster one's completed-arm mark (lost update) → the join's
 * {@code allOf(t_a, t_b)} never becomes true → the journey sticks in RUNNING
 * forever and NO decision is ever published: a silently lost loan application.
 *
 * <p>With generation-CAS the losing writer's save fails, the response is
 * redelivered (Kafka at-least-once — simulated here by the retry loop, exactly
 * what the container error handler does), it re-reads the fresh state, the join
 * fires, and EXACTLY ONE decision is published.
 */
class ParallelJoinTwoReplicasRaceTest {

    private static final String JI = "ji-corr-race";

    /** parallel p → (t_a ‖ t_b) → join j(allOf t_a,t_b) → terminal end. */
    private static JourneyDefinition raceJourney() {
        JourneyNode p = JourneyNode.parallel("p", null, List.of("t_a", "t_b"));
        JourneyNode ta = JourneyNode.task("t_a", null, "capA", null, null, "context.a",
                null, null, null, false, List.of("j"));
        JourneyNode tb = JourneyNode.task("t_b", null, "capB", null, null, "context.b",
                null, null, null, false, List.of("j"));
        JourneyNode j = JourneyNode.join("j", null, List.of("t_a", "t_b"), "allOf", List.of("end"));
        JourneyNode end = JourneyNode.terminal("end", "push_decision_to_channel", List.of(), "completed");
        return new JourneyDefinition("race-journey", 1, "p", List.of(p, ta, tb, j, end));
    }

    /**
     * Forces the deterministic interleaving of two replicas: BOTH response
     * handlers complete their {@code find} (each holding its own snapshot of the
     * same pre-race state) before EITHER saves. Later finds (redelivery retries)
     * pass straight through.
     */
    private static final class TwoReplicasInterleaving implements JourneyInstanceStore {
        private final JourneyInstanceStore delegate;
        private final CyclicBarrier bothLoaded = new CyclicBarrier(2);
        private final AtomicInteger finds = new AtomicInteger();

        TwoReplicasInterleaving(JourneyInstanceStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean insertIfAbsent(JourneyInstance instance) {
            return delegate.insertIfAbsent(instance);
        }

        @Override
        public void save(JourneyInstance instance) {
            delegate.save(instance);
        }

        @Override
        public Optional<JourneyInstance> find(String journeyInstanceId) {
            Optional<JourneyInstance> loaded = delegate.find(journeyInstanceId);
            if (finds.incrementAndGet() <= 2) {
                try {
                    bothLoaded.await();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            return loaded;
        }

        @Override
        public List<JourneyInstance> findRunningStartedBefore(Instant cutoff) {
            return delegate.findRunningStartedBefore(cutoff);
        }
    }

    @Test
    @Timeout(30)
    void concurrentParallelArmResponsesOnTwoReplicasStillFireTheJoinExactlyOnce() throws Exception {
        JourneyInstanceStore store = new TwoReplicasInterleaving(new InMemoryJourneyInstanceStore());
        List<CapabilityRequest> requests = new CopyOnWriteArrayList<>();
        List<JourneyDecision> decisions = new CopyOnWriteArrayList<>();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                FixedJourneySource.registry(Map.of("PL", "race-journey"), raceJourney()),
                store, requests::add, decisions::add, () -> JI);

        // Start: parallel fan-out dispatches both arms.
        orchestrator.onOrigination(Map.of(
                "type", "PL", "correlationId", "corr-race", "applicationRef", "APP-RACE"));
        assertThat(requests).extracting(CapabilityRequest::nodeId).containsExactlyInAnyOrder("t_a", "t_b");

        // Two replicas process the two arms' responses CONCURRENTLY.
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (String nodeId : List.of("t_a", "t_b")) {
            String capability = nodeId.equals("t_a") ? "capA" : "capB";
            Thread replica = new Thread(() -> {
                try {
                    deliverWithRedelivery(orchestrator, new CapabilityResponse(
                            JI, "corr-race", nodeId, capability, CapabilityStatus.OK, Map.of("done", nodeId)));
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }, "replica-" + nodeId);
            replica.start();
        }
        done.await();
        if (failure.get() != null) {
            throw new AssertionError("replica thread failed", failure.get());
        }

        // The join must fire EXACTLY once: one decision, journey COMPLETED —
        // never zero (lost update -> stuck RUNNING) and never two.
        assertThat(decisions)
                .as("join fired exactly once -> exactly one decision published")
                .hasSize(1);
        assertThat(decisions.get(0).outcome()).isEqualTo(JourneyDecision.APPROVED);
        JourneyInstance finalState = store.find(JI).orElseThrow();
        assertThat(finalState.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(finalState.completedNodeIds()).contains("t_a", "t_b", "j", "end");
    }

    /**
     * Kafka is at-least-once: a processing failure (here, a CAS conflict) is
     * redelivered by the container error handler and reprocessed from FRESH
     * state. This loop is that redelivery, faithfully.
     */
    private static void deliverWithRedelivery(JourneyOrchestrator orchestrator, CapabilityResponse response) {
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                orchestrator.onCapabilityResponse(response);
                return;
            } catch (ConcurrentModificationException conflict) {
                // lost the CAS -> redelivery
            }
        }
        throw new AssertionError("response for " + response.nodeId() + " never applied after 10 redeliveries");
    }
}

package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE T1 exactly-once-start gate. Kafka is at-least-once, so a redelivered (or
 * concurrently delivered) origination event must NOT start a second run. The
 * orchestrator derives a DETERMINISTIC instance id from the inbound key and the
 * store's atomic {@code insertIfAbsent} admits exactly one winner — so however
 * many duplicates arrive, the start node is dispatched exactly once.
 *
 * Docker-free: proves the orchestrator+store logic over the in-memory store's
 * real {@code putIfAbsent}. The Aerospike store's CREATE_ONLY primitive has its
 * own concurrency IT.
 */
class JourneyStartIdempotencyTest {

    private final JourneyDefinition def =
            new JourneyDefinitionLoader(new ObjectMapper())
                    .loadFromClasspath("journeys/loan-origination.journey.json");

    @Test
    void concurrentIdenticalOriginationsStartExactlyOnce() throws Exception {
        JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        JourneyRegistry registry = FixedJourneySource.registry(Map.of("PERSONAL_LOAN", def.key()), def);
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();

        // Count how many times the START node (n_customer) is dispatched.
        ConcurrentLinkedQueue<CapabilityRequest> startDispatches = new ConcurrentLinkedQueue<>();
        CapabilityRequestPort requests = r -> {
            if ("n_customer".equals(r.nodeId())) {
                startDispatches.add(r);
            }
        };
        DecisionOutboundPort decisions = d -> { };

        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                engine, registry, store, requests, decisions, () -> "ji-random");

        // The SAME origination (same correlationId) delivered N times concurrently.
        Map<String, Object> envelope = Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-DUP",
                "applicationRef", "APP-1", "payload", Map.of("pan", "ABCDE1234F"));

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();
        ConcurrentLinkedQueue<String> returnedIds = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await(); // maximise contention
                    returnedIds.add(orchestrator.onOrigination(envelope));
                } catch (Throwable t) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();

        assertThat(errors.get()).as("no thread should error").isZero();
        // Every caller resolves the same deterministic instance id.
        assertThat(returnedIds).hasSize(threads);
        assertThat(returnedIds.stream().distinct().toList()).containsExactly("ji-corr-DUP");
        // Exactly ONE start dispatch — the journey ran once despite 32 deliveries.
        assertThat(startDispatches).as("start node dispatched exactly once").hasSize(1);
        // Exactly one instance persisted.
        assertThat(store.find("ji-corr-DUP")).isPresent();
    }

    @Test
    void aSecondSequentialDeliveryIsADuplicateNoOp() {
        JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        JourneyRegistry registry = FixedJourneySource.registry(Map.of("PERSONAL_LOAN", def.key()), def);
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        AtomicInteger starts = new AtomicInteger();
        CapabilityRequestPort requests = r -> {
            if ("n_customer".equals(r.nodeId())) starts.incrementAndGet();
        };
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                engine, registry, store, requests, d -> { }, () -> "ji-random");

        Map<String, Object> envelope = Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-1", "applicationRef", "APP-1");

        String first = orchestrator.onOrigination(envelope);
        String second = orchestrator.onOrigination(envelope); // redelivery

        assertThat(first).isEqualTo(second);
        assertThat(starts.get()).as("redelivery must not start a second run").isEqualTo(1);
    }

    @Test
    void opaqueBodyKeyCannotShadowAnEnvelopeIdentityFieldInTheContext() {
        // Finding #6: the SOAP edge carries the CDATA body OPAQUELY, so a body whose
        // top-level keys collide with envelope identity ("type"/"orgId") must NOT
        // override the platform's routing identity in the journey context.
        JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        JourneyRegistry registry = FixedJourneySource.registry(Map.of("PERSONAL_LOAN", def.key()), def);
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                engine, registry, store, r -> { }, d -> { }, () -> "ji-random");

        Map<String, Object> envelope = new java.util.HashMap<>();
        envelope.put("type", "PERSONAL_LOAN");
        envelope.put("orgId", "ORG-REAL");
        envelope.put("correlationId", "corr-collide");
        envelope.put("applicationRef", "APP-1");
        // A hostile/awkward opaque body carrying its OWN "type"/"orgId" plus a real field.
        envelope.put("payload", Map.of("type", "HACKED", "orgId", "ORG-SPOOF", "customerId", "C1"));

        String id = orchestrator.onOrigination(envelope);
        Map<String, Object> ctx = store.find(id).orElseThrow().payload();

        assertThat(ctx.get("type")).as("envelope identity wins the collision").isEqualTo("PERSONAL_LOAN");
        assertThat(ctx.get("orgId")).isEqualTo("ORG-REAL");
        assertThat(ctx.get("customerId")).as("non-colliding body fields still flow").isEqualTo("C1");
    }
}

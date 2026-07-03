package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeTransition;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workstream B (T2) execution semantics, driven through the PURE engine:
 * anyOf/quorum joins with optional members, per-node retry policies (attempt
 * counting, error-class gating, attempt-suffixed idempotency keys), the
 * per-capability circuit breaker, the compensation saga (reverse completion
 * order, decision-first, saga-terminal), and §7 input mapping.
 */
class JourneyEngineT2Test {

    private final JourneyDefinitionLoader loader = new JourneyDefinitionLoader(new ObjectMapper());

    private JourneyDefinition parse(String json) {
        try {
            return loader.parse(new ObjectMapper().readTree(json));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JourneyInstance instanceFor(JourneyDefinition def) {
        return new JourneyInstance("ji-1", "corr-1", def.key(), 1, "APP-1", Map.of("k", "v"));
    }

    private static CapabilityResponse ok(String node, String cap, Map<String, Object> result) {
        return new CapabilityResponse("ji-1", "corr-1", node, cap, CapabilityStatus.OK, result);
    }

    private static CapabilityResponse error(String node, String cap, ErrorClass errorClass) {
        return new CapabilityResponse("ji-1", "corr-1", node, cap, CapabilityStatus.ERROR,
                Map.of(), errorClass);
    }

    // =======================================================================
    // Joins: anyOf / quorum(n) + optional members
    // =======================================================================

    private static final String FAN_OUT_TEMPLATE = """
            {"journeyKey":"fan","startNodeId":"p",
             "nodes":[
               {"id":"p","type":"parallel","branches":["a","b","c"]},
               {"id":"a","type":"task","capability":"capA","next":["j"]},
               {"id":"b","type":"task","capability":"capB","optional":%s,"next":["j"]},
               {"id":"c","type":"task","capability":"capC","optional":%s,"next":["j"]},
               {"id":"j","type":"join","joinOn":["a","b","c"],"policy":"%s","next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    @Nested
    class Joins {

        private final JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());

        @Test
        void anyOfFiresOnTheFirstCompletion_lateArmsAreNoOps() {
            JourneyDefinition def = parse(FAN_OUT_TEMPLATE.formatted("false", "false", "anyOf"));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            EngineOutcome afterA = engine.onCapabilityResponse(def, instance, ok("a", "capA", Map.of()));
            assertThat(afterA.decision()).as("anyOf fires at the FIRST completed member").isPresent();
            assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);

            EngineOutcome afterB = engine.onCapabilityResponse(def, instance, ok("b", "capB", Map.of()));
            assertThat(afterB.requests()).isEmpty();
            assertThat(afterB.decision()).as("the join must not fire twice").isEmpty();
        }

        @Test
        void quorumFiresExactlyAtN() {
            JourneyDefinition def = parse(FAN_OUT_TEMPLATE.formatted("false", "false", "quorum(2)"));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            assertThat(engine.onCapabilityResponse(def, instance, ok("a", "capA", Map.of()))
                    .decision()).as("1 of 3 < quorum(2)").isEmpty();
            assertThat(engine.onCapabilityResponse(def, instance, ok("b", "capB", Map.of()))
                    .decision()).as("2 of 3 meets quorum(2)").isPresent();
            assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);
        }

        @Test
        void optionalMemberFailureDoesNotBlockTheQuorum() {
            JourneyDefinition def = parse(FAN_OUT_TEMPLATE.formatted("false", "true", "quorum(2)"));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            engine.onCapabilityResponse(def, instance, ok("a", "capA", Map.of()));
            EngineOutcome afterCFails = engine.onCapabilityResponse(def, instance,
                    error("c", "capC", ErrorClass.PERMANENT));
            assertThat(afterCFails.decision())
                    .as("optional failure: 1 completed + 1 outstanding can still reach quorum(2)")
                    .isEmpty();
            assertThat(instance.status()).isEqualTo(InstanceStatus.RUNNING);

            assertThat(engine.onCapabilityResponse(def, instance, ok("b", "capB", Map.of()))
                    .decision()).as("b completes the quorum despite c's failure").isPresent();
            assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);
        }

        @Test
        void unreachableQuorumFailsTheRunInsteadOfHanging() {
            JourneyDefinition def = parse(FAN_OUT_TEMPLATE.formatted("true", "true", "quorum(2)"));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            engine.onCapabilityResponse(def, instance, ok("a", "capA", Map.of()));
            engine.onCapabilityResponse(def, instance, error("b", "capB", ErrorClass.PERMANENT));
            EngineOutcome afterC = engine.onCapabilityResponse(def, instance,
                    error("c", "capC", ErrorClass.PERMANENT));

            assertThat(afterC.decision()).as("1 completed + 0 outstanding < quorum(2): fail NOW,"
                    + " never hang until the sweeper").isPresent();
            assertThat(afterC.decision().orElseThrow().outcome()).isEqualTo(JourneyDecision.ERROR);
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
            assertThat(instance.terminalNodeId()).isEqualTo("j");
        }

        @Test
        void optionalLinearTaskFailureContinuesTheChain() {
            JourneyDefinition def = parse("""
                    {"journeyKey":"lin","startNodeId":"a",
                     "nodes":[
                       {"id":"a","type":"task","capability":"capA","optional":true,"next":["b"]},
                       {"id":"b","type":"task","capability":"capB","next":["end"]},
                       {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                    """);
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            EngineOutcome afterAFails = engine.onCapabilityResponse(def, instance,
                    error("a", "capA", ErrorClass.PERMANENT));
            assertThat(afterAFails.requests())
                    .as("a nice-to-have failure moves the run forward")
                    .singleElement().satisfies(r -> assertThat(r.nodeId()).isEqualTo("b"));
            assertThat(instance.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(instance.isNodeFailed("a")).isTrue();
        }
    }

    // =======================================================================
    // Retry
    // =======================================================================

    private static final String RETRY_JOURNEY = """
            {"journeyKey":"retry","startNodeId":"a",
             "nodes":[
               {"id":"a","type":"task","capability":"flaky",
                "policies":{"retry":{"maxAttempts":%d,
                  "backoff":{"type":"exponential","base":"PT1S","max":"PT8S"},
                  "retryOn":[%s]}},
                "next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    @Nested
    class Retry {

        private final JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());

        @Test
        void transientFailureSchedulesARetryWithExponentialDelay_andAttemptSuffixedKey() {
            JourneyDefinition def = parse(RETRY_JOURNEY.formatted(3, "\"TRANSIENT\""));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);
            assertThat(instance.attemptsOf("a")).isEqualTo(1);

            EngineOutcome retry1 = engine.onCapabilityResponse(def, instance,
                    error("a", "flaky", ErrorClass.TRANSIENT));
            assertThat(retry1.requests()).isEmpty();
            assertThat(retry1.decision()).isEmpty();
            assertThat(retry1.retries()).singleElement().satisfies(r -> {
                assertThat(r.nodeId()).isEqualTo("a");
                assertThat(r.delayMillis()).isEqualTo(1000); // base * 2^0
            });
            assertThat(instance.attemptsOf("a")).isEqualTo(2);
            assertThat(instance.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(instance.isNodeFailed("a")).as("a retryable error is not a node failure").isFalse();

            // The RE-DERIVED request for the retry attempt carries an attempt-suffixed
            // idempotency key — the capability must NOT dedupe it as the failed try.
            assertThat(engine.requestFor(def, instance, "a").idempotencyKey())
                    .isEqualTo("ji-1:a:a2");

            EngineOutcome retry2 = engine.onCapabilityResponse(def, instance,
                    error("a", "flaky", ErrorClass.TRANSIENT));
            assertThat(retry2.retries()).singleElement().satisfies(r ->
                    assertThat(r.delayMillis()).isEqualTo(2000)); // base * 2^1
            assertThat(instance.attemptsOf("a")).isEqualTo(3);

            // Third attempt succeeds — the run completes normally.
            EngineOutcome done = engine.onCapabilityResponse(def, instance, ok("a", "flaky", Map.of()));
            assertThat(done.decision()).isPresent();
            assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);
        }

        @Test
        void exhaustedAttemptsFailTheRun() {
            JourneyDefinition def = parse(RETRY_JOURNEY.formatted(2, "\"TRANSIENT\""));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            assertThat(engine.onCapabilityResponse(def, instance,
                    error("a", "flaky", ErrorClass.TRANSIENT)).retries()).hasSize(1);

            EngineOutcome exhausted = engine.onCapabilityResponse(def, instance,
                    error("a", "flaky", ErrorClass.TRANSIENT));
            assertThat(exhausted.retries()).as("maxAttempts=2 means two dispatches TOTAL").isEmpty();
            assertThat(exhausted.decision()).isPresent();
            assertThat(exhausted.decision().orElseThrow().outcome()).isEqualTo(JourneyDecision.ERROR);
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
            // OPS P2: exhausted retries record the LAST class (TRANSIENT here).
            assertThat(instance.nodeFailureClasses()).containsEntry("a", "TRANSIENT");
        }

        @Test
        void permanentErrorsAreNeverRetried() {
            JourneyDefinition def = parse(RETRY_JOURNEY.formatted(3, "\"TRANSIENT\""));
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);

            EngineOutcome outcome = engine.onCapabilityResponse(def, instance,
                    error("a", "flaky", ErrorClass.PERMANENT));
            assertThat(outcome.retries()).isEmpty();
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
            // OPS P2: the class rides the run record — the ENUM NAME only.
            assertThat(instance.nodeFailureClasses()).containsEntry("a", "PERMANENT");
        }

        @Test
        void unclassifiedErrorsRetryOnlyWhenAmbiguousIsOptedIn() {
            // retryOn [TRANSIENT] only: an unclassified (null) error must NOT retry —
            // it might be a completed write.
            JourneyDefinition strict = parse(RETRY_JOURNEY.formatted(3, "\"TRANSIENT\""));
            JourneyInstance strictInstance = instanceFor(strict);
            engine.start(strict, strictInstance);
            assertThat(engine.onCapabilityResponse(strict, strictInstance,
                    error("a", "flaky", null)).retries()).isEmpty();
            assertThat(strictInstance.status()).isEqualTo(InstanceStatus.FAILED);
            // OPS P2: an UNCLASSIFIED error is reported as AMBIGUOUS, never null/text.
            assertThat(strictInstance.nodeFailureClasses()).containsEntry("a", "AMBIGUOUS");

            // retryOn [TRANSIENT, AMBIGUOUS]: the author opted ambiguous outcomes in.
            JourneyDefinition lenient = parse(RETRY_JOURNEY.formatted(3,
                    "\"TRANSIENT\",\"AMBIGUOUS\""));
            JourneyInstance lenientInstance = instanceFor(lenient);
            engine.start(lenient, lenientInstance);
            assertThat(engine.onCapabilityResponse(lenient, lenientInstance,
                    error("a", "flaky", null)).retries()).hasSize(1);
            assertThat(lenientInstance.status()).isEqualTo(InstanceStatus.RUNNING);
        }
    }

    // =======================================================================
    // Circuit breaker
    // =======================================================================

    /** Deterministic, manually-advanced clock for breaker windows. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-03T10:00:00Z");

        void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final String BREAKER_JOURNEY = """
            {"journeyKey":"cb","startNodeId":"a",
             "nodes":[
               {"id":"a","type":"task","capability":"shaky",
                "policies":{"circuitBreaker":{"failureThreshold":2,"openDuration":"PT30S"}},
                "next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    @Nested
    class Breaker {

        private final MutableClock clock = new MutableClock();
        private final CapabilityCircuitBreakers breakers = new CapabilityCircuitBreakers(clock);
        private final JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator(), breakers);
        private final JourneyDefinition def = parse(BREAKER_JOURNEY);

        private JourneyInstance freshRun(String id) {
            return new JourneyInstance(id, "corr-" + id, def.key(), 1, "APP", Map.of());
        }

        @Test
        void opensAfterConsecutiveFailures_failsFast_thenHalfOpensAndCloses() {
            // Two runs fail the capability -> breaker opens (threshold 2).
            for (String id : new String[]{"ji-a", "ji-b"}) {
                JourneyInstance run = freshRun(id);
                assertThat(engine.start(def, run).requests()).hasSize(1);
                engine.onCapabilityResponse(def, run, new CapabilityResponse(
                        id, "corr-" + id, "a", "shaky", CapabilityStatus.ERROR, Map.of(),
                        ErrorClass.PERMANENT));
            }

            // OPEN: the next run fails FAST — no request even leaves the engine.
            JourneyInstance blocked = freshRun("ji-c");
            EngineOutcome blockedOutcome = engine.start(def, blocked);
            assertThat(blockedOutcome.requests())
                    .as("an OPEN breaker must not dispatch to the struggling capability").isEmpty();
            assertThat(blockedOutcome.decision()).isPresent();
            assertThat(blocked.status()).isEqualTo(InstanceStatus.FAILED);
            // OPS P2: a breaker fast-fail is distinguishable on the run record.
            assertThat(blocked.nodeFailureClasses()).containsEntry("a", "BREAKER_OPEN");

            // Past openDuration: HALF-OPEN lets one probe through...
            clock.advanceSeconds(31);
            JourneyInstance probe = freshRun("ji-d");
            assertThat(engine.start(def, probe).requests()).hasSize(1);
            // ...and its success CLOSES the breaker for everyone.
            engine.onCapabilityResponse(def, probe, new CapabilityResponse(
                    "ji-d", "corr-ji-d", "a", "shaky", CapabilityStatus.OK, Map.of()));
            assertThat(engine.start(def, freshRun("ji-e")).requests()).hasSize(1);
        }
    }

    // =======================================================================
    // Compensation saga
    // =======================================================================

    private static final String SAGA_JOURNEY = """
            {"journeyKey":"saga","startNodeId":"t1",
             "nodes":[
               {"id":"t1","type":"task","capability":"capOne","operation":"doOne",
                "output":"context.r1",
                "compensation":{"operation":"undoOne","input":"{ ref: context.r1.id }"},
                "next":["t2"]},
               {"id":"t2","type":"task","capability":"capTwo","operation":"doTwo",
                "output":"context.r2",
                "compensation":{"operation":"undoTwo"},
                "next":["t3"]},
               {"id":"t3","type":"task","capability":"capThree","onFailure":"compensate",
                "next":["end"]},
               {"id":"end","type":"terminal","action":"push","status":"completed"}]}
            """;

    @Nested
    class Compensation {

        private final JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        private final JourneyDefinition def = parse(SAGA_JOURNEY);

        private JourneyInstance runUpToT3Failure(EngineOutcome[] sagaStartOut) {
            JourneyInstance instance = instanceFor(def);
            engine.start(def, instance);
            engine.onCapabilityResponse(def, instance, ok("t1", "capOne", Map.of("id", "A")));
            engine.onCapabilityResponse(def, instance, ok("t2", "capTwo", Map.of("id", "B")));
            sagaStartOut[0] = engine.onCapabilityResponse(def, instance,
                    error("t3", "capThree", ErrorClass.PERMANENT));
            return instance;
        }

        @Test
        void sagaStartsWithDecisionFirst_thenCompensatesInReverseCompletionOrder() {
            EngineOutcome[] out = new EngineOutcome[1];
            JourneyInstance instance = runUpToT3Failure(out);
            EngineOutcome sagaStart = out[0];

            // The channel is told IMMEDIATELY — compensation is cleanup, not delay.
            assertThat(sagaStart.decision()).isPresent();
            assertThat(sagaStart.decision().orElseThrow().outcome()).isEqualTo(JourneyDecision.ERROR);
            assertThat(sagaStart.decision().orElseThrow().terminalNodeId()).isEqualTo("t3");
            assertThat(instance.status()).isEqualTo(InstanceStatus.COMPENSATING);

            // Reverse completion order: t2 completed last -> undone first.
            assertThat(sagaStart.requests()).singleElement().satisfies(r -> {
                assertThat(r.nodeId()).isEqualTo("t2" + JourneyEngine.COMP_SUFFIX);
                assertThat(r.operation()).isEqualTo("undoTwo");
                assertThat(r.capabilityKey()).isEqualTo("capTwo");
                assertThat(r.idempotencyKey()).isEqualTo("ji-1:t2:comp");
            });
            assertThat(instance.compensationQueue()).containsExactly("t2", "t1");

            // t2 undone -> t1's compensation goes out, with its MAPPED input.
            EngineOutcome afterUndoTwo = engine.onCapabilityResponse(def, instance,
                    ok("t2" + JourneyEngine.COMP_SUFFIX, "capTwo", Map.of()));
            assertThat(afterUndoTwo.requests()).singleElement().satisfies(r -> {
                assertThat(r.nodeId()).isEqualTo("t1" + JourneyEngine.COMP_SUFFIX);
                assertThat(r.operation()).isEqualTo("undoOne");
                assertThat(r.payload()).isEqualTo(Map.of("ref", "A"));
            });

            // t1 undone -> saga complete: terminally FAILED, NO second decision.
            EngineOutcome afterUndoOne = engine.onCapabilityResponse(def, instance,
                    ok("t1" + JourneyEngine.COMP_SUFFIX, "capOne", Map.of()));
            assertThat(afterUndoOne.requests()).isEmpty();
            assertThat(afterUndoOne.decision()).as("the decision went out at saga START").isEmpty();
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
            assertThat(instance.terminalNodeId()).isEqualTo("t3");
            assertThat(instance.transitions())
                    .extracting(NodeTransition::nodeId)
                    .contains("t2" + JourneyEngine.COMP_SUFFIX, "t1" + JourneyEngine.COMP_SUFFIX);
        }

        @Test
        void aFailedCompensationStopsTheSagaVisibly() {
            EngineOutcome[] out = new EngineOutcome[1];
            JourneyInstance instance = runUpToT3Failure(out);

            EngineOutcome afterCompFails = engine.onCapabilityResponse(def, instance,
                    error("t2" + JourneyEngine.COMP_SUFFIX, "capTwo", ErrorClass.PERMANENT));
            assertThat(afterCompFails.requests())
                    .as("a failed UNDO stops the saga — humans take over from the timeline").isEmpty();
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
            assertThat(instance.transitions())
                    .filteredOn(t -> t.nodeId().equals("t2" + JourneyEngine.COMP_SUFFIX)
                            && t.status() == NodeTransition.Status.FAILED)
                    .hasSize(1);
        }

        @Test
        void compensateWithNothingCompletedFailsDirectly() {
            JourneyDefinition direct = parse("""
                    {"journeyKey":"saga2","startNodeId":"t3",
                     "nodes":[
                       {"id":"t3","type":"task","capability":"capThree","onFailure":"compensate",
                        "next":["end"]},
                       {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                    """);
            JourneyInstance instance = instanceFor(direct);
            engine.start(direct, instance);

            EngineOutcome outcome = engine.onCapabilityResponse(direct, instance,
                    error("t3", "capThree", ErrorClass.PERMANENT));
            assertThat(outcome.decision()).isPresent();
            assertThat(outcome.requests()).isEmpty();
            assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
        }
    }

    // =======================================================================
    // §7 input mapping
    // =======================================================================

    @Test
    void inputMappingShapesTheRequestPayloadExactly() {
        JourneyDefinition def = parse("""
                {"journeyKey":"map","startNodeId":"s",
                 "nodes":[
                   {"id":"s","type":"task","capability":"seed","output":"context.seed","next":["a"]},
                   {"id":"a","type":"task","capability":"capA",
                    "input":"{ loanId: context.seed.id, mode: 'FULL', n: 7, force: true }",
                    "next":["end"]},
                   {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """);
        JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
        JourneyInstance instance = instanceFor(def);
        engine.start(def, instance);

        EngineOutcome afterSeed = engine.onCapabilityResponse(def, instance,
                ok("s", "seed", Map.of("id", "X-77")));
        assertThat(afterSeed.requests()).singleElement().satisfies(r -> {
            assertThat(r.nodeId()).isEqualTo("a");
            assertThat(r.payload()).isEqualTo(Map.of(
                    "loanId", "X-77", "mode", "FULL", "n", 7L, "force", true));
        });
    }
}

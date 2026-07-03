package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeTransition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.RetrySpec;
import com.idfcfirstbank.integration.shared.capability.Backoff;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure orchestration core (Charter §8) — NO Spring, NO Kafka. Given a §7
 * journey definition and a mutable {@link JourneyInstance}, it advances the DAG
 * one event at a time and returns an {@link EngineOutcome} (capability requests
 * to publish, retry directives, and/or the final decision).
 *
 * <p>Engine tier T2 executes: task, branch (default + condition), parallel,
 * join ({@code allOf} / {@code anyOf} / {@code quorum(n)}), terminal,
 * {@code condition} gating, {@code onFailure} routing, OPTIONAL tasks (a
 * failure is recorded but the run continues), per-node RETRY policies (the
 * response's {@code errorClass} against {@code retryOn}, exponential backoff
 * via the shared {@link Backoff}), per-capability CIRCUIT BREAKERS (open →
 * fail fast into the normal failure handling), COMPENSATION sagas
 * ({@code onFailure: "compensate"} — ERROR decision immediately, then the
 * completed compensable nodes are compensated in reverse completion order),
 * and §7 INPUT MAPPING on task/compensation requests. Wait/timer/human/
 * foreach/subjourney load but throw here until their tier ships.
 */
public final class JourneyEngine {

    /** Synthetic node-id suffix for compensation requests: {@code n_book#comp}. */
    public static final String COMP_SUFFIX = "#comp";

    private final ExpressionEvaluator evaluator;
    private final CapabilityCircuitBreakers breakers;

    public JourneyEngine(ExpressionEvaluator evaluator) {
        this(evaluator, CapabilityCircuitBreakers.alwaysClosed());
    }

    public JourneyEngine(ExpressionEvaluator evaluator, CapabilityCircuitBreakers breakers) {
        this.evaluator = evaluator;
        this.breakers = breakers;
    }

    public EngineOutcome start(JourneyDefinition def, JourneyInstance instance) {
        EngineOutcome outcome = new EngineOutcome();
        executeNode(def, instance, def.startNode(), outcome);
        return outcome;
    }

    public EngineOutcome onCapabilityResponse(JourneyDefinition def, JourneyInstance instance,
                                              CapabilityResponse response) {
        EngineOutcome outcome = new EngineOutcome();

        // Compensation responses carry the synthetic '#comp' id — saga lane.
        if (response.nodeId() != null && response.nodeId().endsWith(COMP_SUFFIX)) {
            onCompensationResponse(def, instance, response, outcome);
            return outcome;
        }

        JourneyNode node = def.node(response.nodeId());

        if (response.status() == CapabilityStatus.ERROR) {
            breakers.record(node.capability(), node.circuitBreakerSpec(), false);

            // T2 retry: the AUTHOR declared which error classes are worth another
            // attempt. An unclassified error counts as AMBIGUOUS — retried only if
            // explicitly opted in (a possibly-completed write is never blind-retried).
            RetrySpec retry = node.retrySpec();
            if (retry != null && instance.attemptsOf(node.id()) < retry.maxAttempts()
                    && isRetryable(retry, response)) {
                int nextAttempt = instance.bumpAttempt(node.id());
                long delay = backoffOf(retry).delayMillis(nextAttempt - 1);
                outcome.retry(node.id(), delay);
                return outcome;
            }

            instance.recordNodeFailure(response.nodeId(), failureClassOf(response));
            handleNodeFailure(def, instance, node, outcome);
            return outcome;
        }

        breakers.record(node.capability(), node.circuitBreakerSpec(), true);
        instance.recordResult(response.nodeId(), response.capabilityKey(), node.output(), response.result());
        for (String successorId : node.successors()) {
            tryExecute(def, instance, successorId, outcome);
        }
        return outcome;
    }

    /**
     * A node failed FOR GOOD (retries exhausted / none / breaker open). Route it:
     * optional → the run continues (joins see the failed member); onFailure to a
     * node → jump there (T1 semantics preserved: run marked failed first, a
     * recovery terminal overwrites); {@code "compensate"} → start the saga;
     * anything else → fail with the ERROR decision.
     */
    private void handleNodeFailure(JourneyDefinition def, JourneyInstance instance, JourneyNode node,
                                   EngineOutcome outcome) {
        if (node.optional()) {
            // Nice-to-have node: the run advances; quorum/anyOf joins now count
            // this member as failed — fail the run only if a join can never fire.
            for (String successorId : node.successors()) {
                tryExecute(def, instance, successorId, outcome);
            }
            failIfAnyJoinUnsatisfiable(def, instance, outcome);
            return;
        }

        String onFailure = node.onFailure();
        if ("compensate".equals(onFailure)) {
            startCompensation(def, instance, node.id(), outcome);
            return;
        }

        instance.fail(node.id(), JourneyDecision.ERROR);
        if (onFailure != null && isNodeId(def, onFailure)) {
            // T1 failure routing: jump to the recovery node (often a terminal).
            executeNode(def, instance, def.node(onFailure), outcome);
        } else {
            // "dlq"/"fail"/none -> fail the run with the ERROR decision.
            outcome.decide(errorDecision(instance, node.id()));
        }
    }

    // ---- T2: joins -------------------------------------------------------------

    private void tryExecute(JourneyDefinition def, JourneyInstance instance, String nodeId,
                            EngineOutcome outcome) {
        if (instance.isDispatched(nodeId)) {
            return;
        }
        JourneyNode node = def.node(nodeId);
        if (completedCount(node.joinOn(), instance) >= node.joinThreshold()) {
            executeNode(def, instance, node, outcome);
        }
    }

    private static int completedCount(List<String> nodeIds, JourneyInstance instance) {
        int done = 0;
        for (String id : nodeIds) {
            if (instance.isCompleted(id)) {
                done++;
            }
        }
        return done;
    }

    /**
     * After an OPTIONAL member failed: if any not-yet-fired join can NEVER reach
     * its threshold (too many members already failed), the run must fail NOW —
     * an unsatisfiable join would otherwise hang until the sweeper.
     */
    private void failIfAnyJoinUnsatisfiable(JourneyDefinition def, JourneyInstance instance,
                                            EngineOutcome outcome) {
        if (instance.status() != com.idfcfirstbank.integration.orchestration.originationjourney
                .domain.model.InstanceStatus.RUNNING) {
            return;
        }
        for (JourneyNode node : def.nodes()) {
            if (node.type() != NodeType.JOIN || instance.isDispatched(node.id())) {
                continue;
            }
            int completed = completedCount(node.joinOn(), instance);
            int failed = 0;
            for (String member : node.joinOn()) {
                if (instance.isNodeFailed(member) && !instance.isCompleted(member)) {
                    failed++;
                }
            }
            int stillPossible = completed + (node.joinOn().size() - completed - failed);
            if (stillPossible < node.joinThreshold()) {
                instance.fail(node.id(), JourneyDecision.ERROR);
                outcome.decide(errorDecision(instance, node.id()));
                return;
            }
        }
    }

    // ---- T2: retry ---------------------------------------------------------------

    /**
     * OPS P2: the failure class recorded on a terminally-failed node — the
     * ErrorClass ENUM NAME the capability stamped, with an UNCLASSIFIED error
     * reported as AMBIGUOUS (consistent with the retry semantics). Never text.
     */
    private static String failureClassOf(CapabilityResponse response) {
        return response.errorClass() == null ? "AMBIGUOUS" : response.errorClass().name();
    }

    private static boolean isRetryable(RetrySpec retry, CapabilityResponse response) {
        String errorClass = response.errorClass() == null ? "AMBIGUOUS" : response.errorClass().name();
        return retry.retryOn().contains(errorClass);
    }

    private static Backoff backoffOf(RetrySpec retry) {
        return Backoff.exponential(retry.backoffBaseMillis(), retry.backoffMaxMillis(), retry.jitter());
    }

    // ---- T2: compensation saga ------------------------------------------------------

    /**
     * {@code onFailure: "compensate"}: the business outcome is DECIDED (ERROR)
     * and the channel is told in THIS hop — compensation is cleanup, not a
     * second chance. Completed nodes that declare a {@code compensation} are
     * undone strictly in REVERSE COMPLETION ORDER, one at a time. The
     * transition to COMPENSATING rides the same CAS save as every hop
     * (CAS-as-election): a concurrent replica loses the save and its
     * redelivery finds the saga already owned.
     */
    private void startCompensation(JourneyDefinition def, JourneyInstance instance, String failedNodeId,
                                   EngineOutcome outcome) {
        outcome.decide(errorDecision(instance, failedNodeId));
        List<String> queue = compensableInReverseCompletionOrder(def, instance);
        if (queue.isEmpty()) {
            instance.fail(failedNodeId, JourneyDecision.ERROR);
            return;
        }
        instance.startCompensation(failedNodeId, queue);
        outcome.emit(compensationRequest(def, instance, queue.get(0)));
    }

    private void onCompensationResponse(JourneyDefinition def, JourneyInstance instance,
                                        CapabilityResponse response, EngineOutcome outcome) {
        String compNodeId = response.nodeId();
        String originalNodeId = compNodeId.substring(0, compNodeId.length() - COMP_SUFFIX.length());

        if (response.status() == CapabilityStatus.ERROR) {
            // The UNDO itself failed: stop the saga, leave the FAILED timeline row
            // for ops — this is precisely what a human must look at (manual fix).
            instance.recordNodeFailure(compNodeId, failureClassOf(response));
            instance.fail(instance.compensationOf(), JourneyDecision.ERROR);
            return;
        }

        instance.markCompleted(compNodeId);
        String next = instance.advanceCompensation(originalNodeId);
        if (next != null) {
            outcome.emit(compensationRequest(def, instance, next));
        } else {
            // Saga finished — the run is now terminally FAILED (decision already sent).
            instance.fail(instance.compensationOf(), JourneyDecision.ERROR);
        }
    }

    /** Completed compensable TASK nodes, newest completion first (undo in reverse). */
    private static List<String> compensableInReverseCompletionOrder(JourneyDefinition def,
                                                                    JourneyInstance instance) {
        Set<String> ordered = new LinkedHashSet<>();
        for (NodeTransition t : instance.transitions()) {
            if (t.status() != NodeTransition.Status.COMPLETED || t.nodeId().endsWith(COMP_SUFFIX)) {
                continue;
            }
            if (!isNodeId(def, t.nodeId())) {
                continue;
            }
            JourneyNode node = def.node(t.nodeId());
            if (node.type() == NodeType.TASK && node.compensation() != null
                    && instance.isCompleted(t.nodeId())) {
                ordered.add(t.nodeId());
            }
        }
        List<String> reversed = new ArrayList<>(ordered);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private CapabilityRequest compensationRequest(JourneyDefinition def, JourneyInstance instance,
                                                  String nodeId) {
        JourneyNode node = def.node(nodeId);
        Map<String, Object> payload = node.compensation().input() == null
                ? Map.of()
                : evaluator.evaluateMapping(node.compensation().input(), instance.evaluationContext());
        return new CapabilityRequest(
                instance.journeyInstanceId(),
                instance.correlationId(),
                node.capability(),
                nodeId + COMP_SUFFIX,
                payload,
                instance.collectedResults(),
                node.compensation().operation(),
                instance.journeyInstanceId() + ":" + nodeId + ":comp");
    }

    // ---- node execution ------------------------------------------------------------

    private void executeNode(JourneyDefinition def, JourneyInstance instance, JourneyNode node,
                             EngineOutcome outcome) {
        if (instance.isDispatched(node.id())) {
            return;
        }
        instance.markDispatched(node.id());

        // condition gating: if present and false, skip the node and advance.
        if (node.condition() != null && !evaluator.evaluate(node.condition(), instance.evaluationContext())) {
            instance.markCompleted(node.id());
            for (String s : node.successors()) {
                tryExecute(def, instance, s, outcome);
            }
            return;
        }

        switch (node.type()) {
            case TASK -> {
                if (!breakers.allowDispatch(node.capability(), node.circuitBreakerSpec())) {
                    // Breaker OPEN: fail fast into the NORMAL failure handling —
                    // optional / onFailure / compensate semantics all apply.
                    instance.recordNodeFailure(node.id(), "BREAKER_OPEN");
                    handleNodeFailure(def, instance, node, outcome);
                } else {
                    instance.bumpAttempt(node.id());
                    outcome.emit(buildRequest(instance, node));
                }
            }
            case BRANCH -> {
                instance.markCompleted(node.id());
                executeNode(def, instance, def.node(chooseArm(node, instance)), outcome);
            }
            case PARALLEL -> {
                instance.markCompleted(node.id());
                for (String b : node.branches()) {
                    executeNode(def, instance, def.node(b), outcome);
                }
            }
            case JOIN -> {
                instance.markCompleted(node.id());
                for (String s : node.next()) {
                    executeNode(def, instance, def.node(s), outcome);
                }
            }
            case TERMINAL -> {
                instance.markCompleted(node.id());
                String resolved = terminalOutcome(node);
                if (resolved == null) {
                    // Fail CLOSED (Phase 3): an unknown terminal status must never
                    // default to an APPROVED lending decision. The loader rejects
                    // these at load; this guards definitions built any other way.
                    instance.fail(node.id(), JourneyDecision.ERROR);
                    outcome.decide(decisionOf(instance, JourneyDecision.ERROR,
                            loanIdFrom(instance), node.id(), node.emit()));
                } else {
                    instance.complete(node.id(), resolved);
                    outcome.decide(decisionOf(instance, resolved,
                            loanIdFrom(instance), node.id(), node.emit()));
                }
            }
            default -> throw new UnsupportedOperationException(
                    "node type " + node.type() + " ('" + node.id() + "') is not supported in engine tier T2");
        }
    }

    private String chooseArm(JourneyNode branch, JourneyInstance instance) {
        Map<String, Object> ctx = instance.evaluationContext();
        for (BranchArm arm : branch.arms()) {
            if (evaluator.evaluate(arm.when(), ctx)) {
                return arm.next();
            }
        }
        if (branch.defaultNext() != null) {
            return branch.defaultNext();
        }
        // PII: ids only — the evaluation context carries the applicant payload
        // (PAN/mobile/…) and this message ends up in logs and DLQ headers.
        throw new IllegalStateException(
                "no branch arm matched and no default at node '" + branch.id()
                        + "' (journeyInstanceId=" + instance.journeyInstanceId()
                        + ", contextKeys=" + ctx.keySet() + ")");
    }

    /**
     * Re-derive the capability request for a node from persisted state — used to
     * re-drive a pending publish after a crash/redelivery, INCLUDING scheduled
     * retries and compensation requests (synthetic {@code #comp} ids).
     * Deterministic given the definition and instance (attempts are persisted, so
     * the idempotencyKey is stable per attempt) — the capability dedups replays.
     */
    public CapabilityRequest requestFor(JourneyDefinition def, JourneyInstance instance, String nodeId) {
        if (nodeId.endsWith(COMP_SUFFIX)) {
            return compensationRequest(def, instance,
                    nodeId.substring(0, nodeId.length() - COMP_SUFFIX.length()));
        }
        return buildRequest(instance, def.node(nodeId));
    }

    private CapabilityRequest buildRequest(JourneyInstance instance, JourneyNode node) {
        // §7 input mapping (T2): a node with `input` sends EXACTLY the mapped
        // object as its request payload; without it the run payload rides along
        // unchanged (T1 behavior). The idempotencyKey is runId:nodeId for the
        // first attempt and gains an :a<n> suffix per RETRY attempt — a retry
        // must NOT be deduplicated as a replay of the failed attempt, but a
        // redelivery of the SAME attempt must be.
        Map<String, Object> payload = node.input() == null
                ? instance.payload()
                : evaluator.evaluateMapping(node.input(), instance.evaluationContext());
        int attempt = Math.max(1, instance.attemptsOf(node.id()));
        String idempotencyKey = instance.journeyInstanceId() + ":" + node.id()
                + (attempt > 1 ? ":a" + attempt : "");
        return new CapabilityRequest(
                instance.journeyInstanceId(),
                instance.correlationId(),
                node.capability(),
                node.id(),
                payload,
                instance.collectedResults(),
                node.operation(),
                idempotencyKey);
    }

    /**
     * Map a terminal node's declared status to the decision outcome. Returns
     * {@code null} for an UNKNOWN status — the caller fails the run (fail closed);
     * only the three contract statuses may produce a decision, and only
     * {@code completed} may produce APPROVED.
     */
    private static String terminalOutcome(JourneyNode terminal) {
        return switch (terminal.status() == null ? "completed" : terminal.status()) {
            case "completed" -> JourneyDecision.APPROVED;
            case "rejected" -> JourneyDecision.REJECTED;
            case "failed" -> JourneyDecision.ERROR;
            default -> null;
        };
    }

    private JourneyDecision errorDecision(JourneyInstance instance, String terminalNodeId) {
        return decisionOf(instance, JourneyDecision.ERROR, loanIdFrom(instance), terminalNodeId, List.of());
    }

    private JourneyDecision decisionOf(JourneyInstance instance, String outcome, String loanId,
                                       String terminalNodeId, List<String> emitted) {
        return new JourneyDecision(
                instance.journeyInstanceId(), instance.correlationId(), instance.applicationRef(),
                outcome, loanId, terminalNodeId, emitted,
                payloadStr(instance, "source"),
                payloadStr(instance, "notificationId"),
                payloadStr(instance, "sfdcRecordId"));
    }

    /** The booking node binds its result at {@code context.loan}; pull the loan id out. */
    @SuppressWarnings("unchecked")
    private static String loanIdFrom(JourneyInstance instance) {
        Object loan = instance.context().get("loan");
        if (loan instanceof Map<?, ?> m) {
            Object id = ((Map<String, Object>) m).getOrDefault("loanId", ((Map<String, Object>) m).get("id"));
            return id == null ? null : String.valueOf(id);
        }
        return null;
    }

    private static boolean isNodeId(JourneyDefinition def, String value) {
        try {
            def.node(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String payloadStr(JourneyInstance instance, String key) {
        Object v = instance.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }
}

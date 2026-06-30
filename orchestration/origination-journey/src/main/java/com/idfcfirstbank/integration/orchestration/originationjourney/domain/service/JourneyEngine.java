package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;

import java.util.List;
import java.util.Map;

/**
 * The pure orchestration core (Charter §8) — NO Spring, NO Kafka. Given a §7
 * journey definition and a mutable {@link JourneyInstance}, it advances the DAG
 * one event at a time and returns an {@link EngineOutcome} (capability requests
 * to publish and/or the final decision).
 *
 * <p>Engine tier T1 executes: task, branch (with default + condition), parallel,
 * join (allOf), terminal, plus {@code condition} gating and {@code onFailure}
 * routing to a node. Other §7 node kinds (wait/timer/human/foreach/subjourney)
 * load but throw here until their tier ships. Saga {@code compensation} and the
 * {@code meter}/retry/circuitBreaker policies are authored now, enforced in T2.
 */
public final class JourneyEngine {

    private final ExpressionEvaluator evaluator;

    public JourneyEngine(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public EngineOutcome start(JourneyDefinition def, JourneyInstance instance) {
        EngineOutcome outcome = new EngineOutcome();
        executeNode(def, instance, def.startNode(), outcome);
        return outcome;
    }

    public EngineOutcome onCapabilityResponse(JourneyDefinition def, JourneyInstance instance,
                                              CapabilityResponse response) {
        EngineOutcome outcome = new EngineOutcome();
        JourneyNode node = def.node(response.nodeId());

        if (response.status() == CapabilityStatus.ERROR) {
            instance.fail();
            String onFailure = node.onFailure();
            if (onFailure != null && isNodeId(def, onFailure)) {
                // T1 failure routing: jump to the recovery node (often a terminal).
                executeNode(def, instance, def.node(onFailure), outcome);
            } else {
                // "compensate"/"dlq"/"fail"/none -> fail (saga compensation is T2).
                outcome.decide(errorDecision(instance, response.nodeId()));
            }
            return outcome;
        }

        instance.recordResult(response.nodeId(), response.capabilityKey(), node.output(), response.result());
        for (String successorId : node.successors()) {
            tryExecute(def, instance, successorId, outcome);
        }
        return outcome;
    }

    private void tryExecute(JourneyDefinition def, JourneyInstance instance, String nodeId,
                            EngineOutcome outcome) {
        if (instance.isDispatched(nodeId)) {
            return;
        }
        JourneyNode node = def.node(nodeId);
        boolean ready = node.joinOn().stream().allMatch(instance::isCompleted);
        if (ready) {
            executeNode(def, instance, node, outcome);
        }
    }

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
            case TASK -> outcome.emit(buildRequest(instance, node));
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
                instance.complete();
                outcome.decide(buildDecision(instance, node));
            }
            default -> throw new UnsupportedOperationException(
                    "node type " + node.type() + " ('" + node.id() + "') is not supported in engine tier T1");
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
        throw new IllegalStateException(
                "no branch arm matched and no default at node '" + branch.id() + "' (context=" + ctx + ")");
    }

    private CapabilityRequest buildRequest(JourneyInstance instance, JourneyNode node) {
        // The §7 node's `operation` is now transmitted so a multi-operation
        // capability can dispatch on it; idempotencyKey = runId:nodeId makes a
        // redelivered capability request a no-op at the capability (BRD §2).
        return new CapabilityRequest(
                instance.journeyInstanceId(),
                instance.correlationId(),
                node.capability(),
                node.id(),
                instance.payload(),
                instance.collectedResults(),
                node.operation(),
                instance.journeyInstanceId() + ":" + node.id());
    }

    private JourneyDecision buildDecision(JourneyInstance instance, JourneyNode terminal) {
        String outcome = switch (terminal.status() == null ? "completed" : terminal.status()) {
            case "rejected" -> JourneyDecision.REJECTED;
            case "failed" -> JourneyDecision.ERROR;
            default -> JourneyDecision.APPROVED;
        };
        return decisionOf(instance, outcome, loanIdFrom(instance), terminal.id(), terminal.emit());
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

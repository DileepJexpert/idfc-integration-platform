package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;

import java.util.Map;

/**
 * The pure orchestration core — NO Spring, NO Kafka. Given a journey definition
 * and a mutable {@link JourneyInstance}, it advances the DAG one event at a time
 * and returns an {@link EngineOutcome} (capability requests to publish and/or the
 * final decision). The adapters do the I/O; this is fully unit-testable.
 *
 * <p>Execution model (mirrors the DAG Designer's preview engine, but real and
 * async): a task node dispatches a capability request and waits for its response;
 * a branch node routes synchronously by evaluating its arms; a terminal node ends
 * the run with a decision. A node with {@code joinOn} waits for all listed
 * predecessors before it runs; a task with multiple {@code next} fans out.
 */
public final class JourneyEngine {

    private final ExpressionEvaluator evaluator;

    public JourneyEngine(ExpressionEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Begin a run: execute the start node. */
    public EngineOutcome start(JourneyDefinition def, JourneyInstance instance) {
        EngineOutcome outcome = new EngineOutcome();
        executeNode(def, instance, def.startNode(), outcome);
        return outcome;
    }

    /** Advance a run on a capability response. */
    public EngineOutcome onCapabilityResponse(JourneyDefinition def, JourneyInstance instance,
                                              CapabilityResponse response) {
        EngineOutcome outcome = new EngineOutcome();

        if (response.status() == CapabilityStatus.ERROR) {
            instance.fail();
            outcome.decide(new JourneyDecision(instance.journeyInstanceId(), instance.correlationId(),
                    instance.applicationRef(), JourneyDecision.ERROR, null, response.nodeId(),
                    java.util.List.of()));
            return outcome;
        }

        instance.recordResult(response.nodeId(), response.capabilityKey(), response.result());

        // Try to advance into every successor that is now ready.
        for (String successorId : def.node(response.nodeId()).successors()) {
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

        switch (node.type()) {
            case TASK -> outcome.emit(buildRequest(instance, node));
            case BRANCH -> {
                instance.markCompleted(node.id());
                executeNode(def, instance, def.node(chooseArm(node, instance)), outcome);
            }
            case TERMINAL -> {
                instance.markCompleted(node.id());
                instance.complete();
                outcome.decide(buildDecision(instance, node));
            }
        }
    }

    private String chooseArm(JourneyNode branch, JourneyInstance instance) {
        Map<String, Object> ctx = instance.evaluationContext();
        for (BranchArm arm : branch.arms()) {
            if (evaluator.evaluate(arm.expression(), ctx)) {
                return arm.next();
            }
        }
        throw new IllegalStateException(
                "no branch arm matched at node '" + branch.id() + "' (context=" + ctx + ")");
    }

    private CapabilityRequest buildRequest(JourneyInstance instance, JourneyNode node) {
        return new CapabilityRequest(
                instance.journeyInstanceId(),
                instance.correlationId(),
                node.capabilityKey(),
                node.id(),
                instance.payload(),
                instance.collectedResults());
    }

    private JourneyDecision buildDecision(JourneyInstance instance, JourneyNode terminal) {
        Map<String, Object> ctx = instance.evaluationContext();
        String outcome = resolveOutcome(ctx, terminal);
        String loanId = ctx.get("loanId") == null ? null : String.valueOf(ctx.get("loanId"));
        return new JourneyDecision(instance.journeyInstanceId(), instance.correlationId(),
                instance.applicationRef(), outcome, loanId, terminal.id(), terminal.emit());
    }

    /** Prefer the explicit scoring decision; fall back to the terminal's emitted event. */
    private String resolveOutcome(Map<String, Object> ctx, JourneyNode terminal) {
        Object decision = ctx.get("decision");
        if (JourneyDecision.APPROVED.equals(decision)) {
            return JourneyDecision.APPROVED;
        }
        if (JourneyDecision.REJECTED.equals(decision)) {
            return JourneyDecision.REJECTED;
        }
        if (terminal.emit().contains("LoanBooked")) {
            return JourneyDecision.APPROVED;
        }
        if (terminal.emit().contains("LoanRejected")) {
            return JourneyDecision.REJECTED;
        }
        return decision == null ? JourneyDecision.ERROR : String.valueOf(decision);
    }
}

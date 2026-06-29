package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.List;

/**
 * A single DAG node, parsed from the locked journey config. One fat record
 * covers all three {@link NodeType}s (only the relevant fields are populated)
 * so loading stays a straight JSON map — the field names match the contract
 * exactly: {@code id, type, capabilityKey, next, joinOn, meter, compensation,
 * optional, arms, action, emit}.
 */
public record JourneyNode(
        String id,
        NodeType type,
        String capabilityKey,
        List<String> next,
        List<String> joinOn,
        String meter,
        String compensation,
        boolean optional,
        List<BranchArm> arms,
        String action,
        List<String> emit) {

    public JourneyNode {
        next = next == null ? List.of() : List.copyOf(next);
        joinOn = joinOn == null ? List.of() : List.copyOf(joinOn);
        arms = arms == null ? List.of() : List.copyOf(arms);
        emit = emit == null ? List.of() : List.copyOf(emit);
    }

    /** Direct successor node ids: {@code next} for a task, arm targets for a branch. */
    public List<String> successors() {
        return switch (type) {
            case TASK -> next;
            case BRANCH -> arms.stream().map(BranchArm::next).toList();
            case TERMINAL -> List.of();
        };
    }

    public boolean isMetered() {
        return meter != null && !meter.isBlank();
    }
}

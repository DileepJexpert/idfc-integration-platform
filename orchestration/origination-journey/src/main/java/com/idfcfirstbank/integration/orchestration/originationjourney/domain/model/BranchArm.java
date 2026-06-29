package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/**
 * One arm of a {@link NodeType#BRANCH} node: a boolean {@code expression}
 * evaluated against the run context, and the {@code next} node id to route to
 * when it is the first arm to match.
 */
public record BranchArm(String expression, String next) {
}

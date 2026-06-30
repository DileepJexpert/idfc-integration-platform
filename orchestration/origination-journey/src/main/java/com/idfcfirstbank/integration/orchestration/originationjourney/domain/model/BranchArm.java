package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/**
 * One arm of a {@link NodeType#BRANCH} node (Charter §2): a boolean {@code when}
 * expression over {@code context}, and the {@code next} node id to route to when
 * it is the first arm to match.
 */
public record BranchArm(String when, String next) {
}

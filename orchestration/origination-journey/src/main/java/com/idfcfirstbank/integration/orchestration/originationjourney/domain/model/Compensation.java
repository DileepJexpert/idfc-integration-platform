package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/**
 * A saga compensation (Charter §4): the undo {@code operation} on the SAME
 * capability as the task, with an optional {@code input} expression. Executed in
 * reverse order on failure (engine tier T2).
 */
public record Compensation(String operation, String input) {
}

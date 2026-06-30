package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/** DAG node kinds (Charter §2). The engine executes the T1 subset (task,
 * branch, parallel, join, terminal); the rest parse but are gated until their
 * tier ships. */
public enum NodeType {
    TASK, BRANCH, PARALLEL, JOIN, WAIT, TIMER, HUMAN, FOREACH, SUBJOURNEY, TERMINAL
}

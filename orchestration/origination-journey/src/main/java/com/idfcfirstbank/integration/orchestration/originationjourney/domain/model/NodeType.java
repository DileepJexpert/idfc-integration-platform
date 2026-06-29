package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/** DAG node kinds, matching the locked config schema's {@code "type"} field. */
public enum NodeType {
    TASK,
    BRANCH,
    TERMINAL
}

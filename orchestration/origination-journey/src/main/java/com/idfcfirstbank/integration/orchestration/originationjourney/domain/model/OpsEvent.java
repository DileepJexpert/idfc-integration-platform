package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.time.Instant;

/**
 * One run-lifecycle event for the observability stack (B.2, topic
 * {@code ops.journey.events.v1}). STRICTLY ids + nodeIds + timestamps — the
 * record has no payload-shaped field, so a PII leak is structurally impossible
 * (D13). The ops read-API never depends on these events; they exist for
 * SENTINEL/alerting.
 */
public record OpsEvent(
        String event,
        String journeyInstanceId,
        String journeyKey,
        int journeyVersion,
        String nodeId,
        String outcome,
        String correlationId,
        Instant at) {

    public static final String RUN_STARTED = "run.started";
    public static final String NODE_DISPATCHED = "node.dispatched";
    public static final String NODE_COMPLETED = "node.completed";
    public static final String NODE_FAILED = "node.failed";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_SWEPT_TIMEOUT = "run.sweptTimeout";

    public static OpsEvent run(String event, JourneyInstance i, String outcome) {
        return new OpsEvent(event, i.journeyInstanceId(), i.journeyKey(), i.journeyVersion(),
                null, outcome, i.correlationId(), Instant.now());
    }

    public static OpsEvent node(String event, JourneyInstance i, String nodeId) {
        return new OpsEvent(event, i.journeyInstanceId(), i.journeyKey(), i.journeyVersion(),
                nodeId, null, i.correlationId(), Instant.now());
    }
}

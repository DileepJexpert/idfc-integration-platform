package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Mutable run state for one journey instance. The pure {@link
 * com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine}
 * advances this as capability responses arrive; an OUT port persists it between
 * the async hops.
 *
 * <p>Results are keyed by {@code capabilityKey} (so a downstream capability can
 * read, e.g., {@code collectedResults().get("bureau")}); node completion is
 * tracked separately by node id (for joins and to prevent double-dispatch).
 */
public final class JourneyInstance {

    private final String journeyInstanceId;
    private final String correlationId;
    private final String journeyKey;
    private final String applicationRef;
    private final Map<String, Object> payload;

    private final Map<String, Object> collectedResults = new LinkedHashMap<>();
    private final Set<String> completedNodeIds = new HashSet<>();
    private final Set<String> dispatchedNodeIds = new HashSet<>();
    private InstanceStatus status = InstanceStatus.RUNNING;

    public JourneyInstance(String journeyInstanceId, String correlationId, String journeyKey,
                           String applicationRef, Map<String, Object> payload) {
        this.journeyInstanceId = journeyInstanceId;
        this.correlationId = correlationId;
        this.journeyKey = journeyKey;
        this.applicationRef = applicationRef;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
    }

    public String journeyInstanceId() { return journeyInstanceId; }
    public String correlationId() { return correlationId; }
    public String journeyKey() { return journeyKey; }
    public String applicationRef() { return applicationRef; }
    public Map<String, Object> payload() { return payload; }
    public Map<String, Object> collectedResults() { return collectedResults; }
    public InstanceStatus status() { return status; }

    public boolean isDispatched(String nodeId) { return dispatchedNodeIds.contains(nodeId); }
    public void markDispatched(String nodeId) { dispatchedNodeIds.add(nodeId); }
    public boolean isCompleted(String nodeId) { return completedNodeIds.contains(nodeId); }

    /** Record a node's result and mark it complete. Results are keyed by capability. */
    public void recordResult(String nodeId, String capabilityKey, Map<String, Object> result) {
        completedNodeIds.add(nodeId);
        if (capabilityKey != null && result != null) {
            collectedResults.put(capabilityKey, result);
        }
    }

    /** Mark a non-task node (branch/terminal) as processed. */
    public void markCompleted(String nodeId) { completedNodeIds.add(nodeId); }

    public void complete() { this.status = InstanceStatus.COMPLETED; }
    public void fail() { this.status = InstanceStatus.FAILED; }

    /**
     * Flat evaluation context for branch expressions: the payload overlaid with
     * every collected result map. So {@code decision} (from the scoring result)
     * and {@code score} are reachable by bare name.
     */
    public Map<String, Object> evaluationContext() {
        Map<String, Object> ctx = new LinkedHashMap<>(payload);
        for (Object value : collectedResults.values()) {
            if (value instanceof Map<?, ?> m) {
                m.forEach((k, v) -> ctx.put(String.valueOf(k), v));
            }
        }
        return ctx;
    }
}

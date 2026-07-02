package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    /**
     * The PINNED definition version this run started on (A2): every later hop
     * resolves {@code journeyKey@journeyVersion}, so a publish mid-run never
     * changes a running journey. {@code 0} = legacy instance persisted before
     * pinning existed (resolved to current, with a warn).
     */
    private final int journeyVersion;
    private final String applicationRef;
    private final Map<String, Object> payload;
    /** When this run started — used by the liveness sweeper to find stuck RUNNING instances. */
    private final Instant startedAt;

    private final Map<String, Object> collectedResults = new LinkedHashMap<>();
    /** The typed §7 run document: nodes write their `output` here; expressions read it. */
    private final Map<String, Object> context = new LinkedHashMap<>();
    private final Set<String> completedNodeIds = new HashSet<>();
    private final Set<String> dispatchedNodeIds = new HashSet<>();
    private InstanceStatus status = InstanceStatus.RUNNING;

    /**
     * Optimistic-lock version for the store's compare-and-set save: the version
     * this state was LOADED at (0 = not yet persisted). A save must only apply if
     * the store still holds this version — a concurrent writer (second engine
     * replica) must lose and redeliver, never blindly overwrite.
     */
    private long version;

    /**
     * The publish INTENT persisted BEFORE side effects (a lightweight outbox):
     * node ids whose capability requests must go out, and the decision (if any).
     * Written with the same save that records the engine's state advance; cleared
     * by a second save once every publish is CONFIRMED. A crash between the two
     * leaves the intent durable, so the redelivered trigger re-drives the
     * publishes instead of losing the hop.
     */
    private final List<String> pendingRequestNodeIds = new ArrayList<>();
    private JourneyDecision pendingDecision;

    public JourneyInstance(String journeyInstanceId, String correlationId, String journeyKey,
                           int journeyVersion, String applicationRef, Map<String, Object> payload) {
        this(journeyInstanceId, correlationId, journeyKey, journeyVersion, applicationRef, payload,
                Instant.now());
    }

    public JourneyInstance(String journeyInstanceId, String correlationId, String journeyKey,
                           int journeyVersion, String applicationRef, Map<String, Object> payload,
                           Instant startedAt) {
        this.journeyInstanceId = journeyInstanceId;
        this.correlationId = correlationId;
        this.journeyKey = journeyKey;
        this.journeyVersion = journeyVersion;
        this.applicationRef = applicationRef;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public String journeyInstanceId() { return journeyInstanceId; }
    public String correlationId() { return correlationId; }
    public String journeyKey() { return journeyKey; }
    public int journeyVersion() { return journeyVersion; }
    public String applicationRef() { return applicationRef; }
    public Map<String, Object> payload() { return payload; }
    public Instant startedAt() { return startedAt; }
    public Map<String, Object> collectedResults() { return collectedResults; }
    public Map<String, Object> context() { return context; }
    public InstanceStatus status() { return status; }

    public boolean isDispatched(String nodeId) { return dispatchedNodeIds.contains(nodeId); }
    public void markDispatched(String nodeId) { dispatchedNodeIds.add(nodeId); }
    public boolean isCompleted(String nodeId) { return completedNodeIds.contains(nodeId); }

    public long version() { return version; }
    public void version(long version) { this.version = version; }

    public List<String> pendingRequestNodeIds() { return List.copyOf(pendingRequestNodeIds); }
    public JourneyDecision pendingDecision() { return pendingDecision; }
    public boolean hasPendingPublishes() { return !pendingRequestNodeIds.isEmpty() || pendingDecision != null; }

    /** Record the publish intent for this hop (persisted with the state save). */
    public void setPendingPublishes(List<String> requestNodeIds, JourneyDecision decision) {
        pendingRequestNodeIds.clear();
        if (requestNodeIds != null) {
            pendingRequestNodeIds.addAll(requestNodeIds);
        }
        this.pendingDecision = decision;
    }

    /** All publishes confirmed — clear the intent (persisted by the follow-up save). */
    public void clearPendingPublishes() {
        pendingRequestNodeIds.clear();
        pendingDecision = null;
    }

    /** Read-only views of the tracking sets, for persistence. */
    public Set<String> completedNodeIds() { return Set.copyOf(completedNodeIds); }
    public Set<String> dispatchedNodeIds() { return Set.copyOf(dispatchedNodeIds); }

    /**
     * Rehydrate a persisted instance (used by a durable {@code JourneyInstanceStore}
     * adapter). Restores the full run state — collected results, the completed and
     * dispatched node sets, and the status — so the engine resumes exactly where it
     * left off across the async hops.
     */
    public static JourneyInstance restore(String journeyInstanceId, String correlationId, String journeyKey,
                                          int journeyVersion,
                                          String applicationRef, Map<String, Object> payload, Instant startedAt,
                                          long version,
                                          Map<String, Object> collectedResults, Map<String, Object> context,
                                          Set<String> completedNodeIds,
                                          Set<String> dispatchedNodeIds, InstanceStatus status,
                                          List<String> pendingRequestNodeIds, JourneyDecision pendingDecision) {
        JourneyInstance instance = new JourneyInstance(journeyInstanceId, correlationId, journeyKey,
                journeyVersion, applicationRef, payload, startedAt);
        instance.version = version;
        if (collectedResults != null) {
            instance.collectedResults.putAll(collectedResults);
        }
        if (context != null) {
            instance.context.putAll(context);
        }
        if (completedNodeIds != null) {
            instance.completedNodeIds.addAll(completedNodeIds);
        }
        if (dispatchedNodeIds != null) {
            instance.dispatchedNodeIds.addAll(dispatchedNodeIds);
        }
        if (status != null) {
            instance.status = status;
        }
        if (pendingRequestNodeIds != null) {
            instance.pendingRequestNodeIds.addAll(pendingRequestNodeIds);
        }
        instance.pendingDecision = pendingDecision;
        return instance;
    }

    /**
     * Record a node's result and mark it complete. Results are keyed by capability
     * (so downstream tasks read {@code collectedResults().get("bureau")}) AND bound
     * into the typed {@code context} at the node's {@code output} key (so §7
     * expressions like {@code context.scoring.decision} resolve).
     */
    public void recordResult(String nodeId, String capabilityKey, String output, Map<String, Object> result) {
        completedNodeIds.add(nodeId);
        if (capabilityKey != null && result != null) {
            collectedResults.put(capabilityKey, result);
        }
        if (output != null && result != null) {
            String key = output.startsWith("context.") ? output.substring("context.".length()) : output;
            if (!key.isBlank()) {
                context.put(key, result);
            }
        }
    }

    /** Mark a non-task node (branch/terminal) as processed. */
    public void markCompleted(String nodeId) { completedNodeIds.add(nodeId); }

    public void complete() { this.status = InstanceStatus.COMPLETED; }
    public void fail() { this.status = InstanceStatus.FAILED; }

    /**
     * The §7 evaluation root for expressions: a map whose {@code context} entry is
     * the typed run document (so {@code context.scoring.decision} navigates into
     * the bound results), plus the inbound identity fields under {@code context}
     * for convenience.
     */
    public Map<String, Object> evaluationContext() {
        Map<String, Object> ctx = new LinkedHashMap<>(payload);
        ctx.putAll(context);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("context", ctx);
        return root;
    }
}

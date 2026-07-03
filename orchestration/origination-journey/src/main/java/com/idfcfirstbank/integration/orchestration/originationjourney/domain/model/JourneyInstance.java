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
    /**
     * Nodes that FAILED for good — retries exhausted or none configured (T2).
     * Joins read this: a quorum/anyOf join counts completed members against its
     * threshold and uses the failed set to detect a join that can NEVER fire.
     */
    private final Set<String> failedNodeIds = new HashSet<>();
    /**
     * Dispatch attempts per task node (T2 retry): bumped every time the engine
     * decides to (re)dispatch the node's capability request. Persisted so a
     * redelivered hop re-derives the SAME idempotency key for the same attempt.
     */
    private final Map<String, Integer> dispatchAttempts = new LinkedHashMap<>();
    /**
     * The compensation saga's remaining work (T2), in dispatch order (reverse
     * completion order of the compensable nodes). Head = in flight.
     */
    private final List<String> compensationQueue = new ArrayList<>();
    /**
     * OPS P2: nodeId -> failure-class ENUM NAME for terminally-failed nodes
     * (TRANSIENT exhausted / PERMANENT / AMBIGUOUS / BREAKER_OPEN). Names only —
     * a free-text reason could carry PII and never enters this record.
     */
    private final Map<String, String> nodeFailureClasses = new LinkedHashMap<>();
    /** The node whose failure STARTED the compensation saga (the run's terminal node). */
    private String compensationOf;
    private InstanceStatus status = InstanceStatus.RUNNING;

    /**
     * Per-node transition history (ops timeline, B.1). Append-only and BOUNDED;
     * list order IS the event sequence (D11). Appends are idempotent by
     * {@code (nodeId, status)} — at-least-once redelivery never duplicates a
     * timeline row (D10); a transition after the run ended is kept but flagged
     * {@code late}, never visually reopening the run.
     */
    private final List<NodeTransition> transitions = new ArrayList<>();
    private static final int MAX_TRANSITIONS = 200;

    /** When the run reached a terminal state (null while RUNNING). */
    private Instant endedAt;
    /** The terminal node that ended the run ({@code __timeout__} for swept runs). */
    private String terminalNodeId;
    /** The decision outcome the run ended with (APPROVED/REJECTED/ERROR). */
    private String terminalOutcome;

    /**
     * Whether the channel (SFDC/partner) has been told this run's outcome —
     * the single most triage-relevant bit in the assisted model: a FAILED run
     * the agent was never told about will NEVER be re-sent externally.
     * NONE while running, PENDING once a decision awaits its confirmed
     * publish, SENT once the publish was confirmed.
     */
    public enum NotifyState { NONE, PENDING, SENT }

    private NotifyState sfdcNotified = NotifyState.NONE;

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

    public void markDispatched(String nodeId) {
        dispatchedNodeIds.add(nodeId);
        recordTransition(nodeId, NodeTransition.Status.DISPATCHED);
    }

    public boolean isCompleted(String nodeId) { return completedNodeIds.contains(nodeId); }

    public boolean isNodeFailed(String nodeId) { return failedNodeIds.contains(nodeId); }

    /**
     * A node-level TERMINAL failure (retries exhausted or none): timeline row +
     * the failed set joins read. The run-level outcome is the caller's decision
     * (fail / continue-optional / compensate).
     */
    public void recordNodeFailure(String nodeId) {
        failedNodeIds.add(nodeId);
        recordTransition(nodeId, NodeTransition.Status.FAILED);
    }

    /**
     * OPS P2: failure recorded WITH its class — the ENUM NAME only (TRANSIENT /
     * PERMANENT / AMBIGUOUS / BREAKER_OPEN), never a message: exception text is
     * the classic PII smuggling route and stays off this record entirely.
     */
    public void recordNodeFailure(String nodeId, String failureClass) {
        recordNodeFailure(nodeId);
        if (failureClass != null && !failureClass.isBlank()) {
            nodeFailureClasses.put(nodeId, failureClass);
        }
    }

    /** nodeId -> failure-class ENUM NAME (ops read model; empty for old records). */
    public Map<String, String> nodeFailureClasses() { return Map.copyOf(nodeFailureClasses); }

    // ---- T2: retry attempts --------------------------------------------------

    /** Dispatches of this node so far (0 = never dispatched). */
    public int attemptsOf(String nodeId) {
        return dispatchAttempts.getOrDefault(nodeId, 0);
    }

    /** Count one (re)dispatch decision for the node; returns the new attempt number. */
    public int bumpAttempt(String nodeId) {
        int next = attemptsOf(nodeId) + 1;
        dispatchAttempts.put(nodeId, next);
        return next;
    }

    public Map<String, Integer> dispatchAttempts() { return Map.copyOf(dispatchAttempts); }

    // ---- T2: compensation saga -------------------------------------------------

    /** Enter the saga: business outcome is already decided (ERROR); comps remain. */
    public void startCompensation(String failedNodeId, List<String> queue) {
        this.status = InstanceStatus.COMPENSATING;
        this.compensationOf = failedNodeId;
        this.compensationQueue.clear();
        this.compensationQueue.addAll(queue);
    }

    public List<String> compensationQueue() { return List.copyOf(compensationQueue); }

    public String compensationOf() { return compensationOf; }

    /** The comp at the head of the queue finished — remove it; returns the next head or null. */
    public String advanceCompensation(String finishedNodeId) {
        compensationQueue.remove(finishedNodeId);
        return compensationQueue.isEmpty() ? null : compensationQueue.get(0);
    }

    /**
     * Idempotent, bounded timeline append (D10): one row per {@code (nodeId,
     * status)} ever; rows after the run ended are flagged {@code late}.
     */
    private void recordTransition(String nodeId, NodeTransition.Status transitionStatus) {
        if (transitions.size() >= MAX_TRANSITIONS) {
            return; // bounded: a runaway loop must not grow the record forever
        }
        for (NodeTransition t : transitions) {
            if (t.nodeId().equals(nodeId) && t.status() == transitionStatus) {
                return; // redelivered hop — the timeline row already exists
            }
        }
        transitions.add(new NodeTransition(nodeId, transitionStatus, Instant.now(), endedAt != null));
    }

    /** The run's per-node timeline, in EVENT-SEQUENCE order (read-only view). */
    public List<NodeTransition> transitions() { return List.copyOf(transitions); }

    public Instant endedAt() { return endedAt; }
    public String terminalNodeId() { return terminalNodeId; }
    public String terminalOutcome() { return terminalOutcome; }
    public NotifyState sfdcNotified() { return sfdcNotified; }

    /** The channel notify was CONFIRMED (decision publish acked / sweeper notify sent). */
    public void markSfdcNotified() { this.sfdcNotified = NotifyState.SENT; }

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
        if (decision != null) {
            // The channel notify now EXISTS but is not yet confirmed-published.
            this.sfdcNotified = NotifyState.PENDING;
        }
    }

    /** All publishes confirmed — clear the intent (persisted by the follow-up save). */
    public void clearPendingPublishes() {
        if (pendingDecision != null) {
            // The decision publish was CONFIRMED — the channel has been told.
            this.sfdcNotified = NotifyState.SENT;
        }
        pendingRequestNodeIds.clear();
        pendingDecision = null;
    }

    /** Read-only views of the tracking sets, for persistence. */
    public Set<String> completedNodeIds() { return Set.copyOf(completedNodeIds); }
    public Set<String> dispatchedNodeIds() { return Set.copyOf(dispatchedNodeIds); }
    public Set<String> failedNodeIds() { return Set.copyOf(failedNodeIds); }

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
                                          List<String> pendingRequestNodeIds, JourneyDecision pendingDecision,
                                          List<NodeTransition> transitions, Instant endedAt,
                                          String terminalNodeId, String terminalOutcome,
                                          NotifyState sfdcNotified) {
        return restore(journeyInstanceId, correlationId, journeyKey, journeyVersion, applicationRef,
                payload, startedAt, version, collectedResults, context, completedNodeIds,
                dispatchedNodeIds, status, pendingRequestNodeIds, pendingDecision, transitions, endedAt,
                terminalNodeId, terminalOutcome, sfdcNotified, null, null, null, null);
    }

    /** T2 form: adds the failed-node set, retry attempts and the compensation saga state. */
    public static JourneyInstance restore(String journeyInstanceId, String correlationId, String journeyKey,
                                          int journeyVersion,
                                          String applicationRef, Map<String, Object> payload, Instant startedAt,
                                          long version,
                                          Map<String, Object> collectedResults, Map<String, Object> context,
                                          Set<String> completedNodeIds,
                                          Set<String> dispatchedNodeIds, InstanceStatus status,
                                          List<String> pendingRequestNodeIds, JourneyDecision pendingDecision,
                                          List<NodeTransition> transitions, Instant endedAt,
                                          String terminalNodeId, String terminalOutcome,
                                          NotifyState sfdcNotified,
                                          Set<String> failedNodeIds, Map<String, Integer> dispatchAttempts,
                                          List<String> compensationQueue, String compensationOf) {
        return restore(journeyInstanceId, correlationId, journeyKey, journeyVersion, applicationRef,
                payload, startedAt, version, collectedResults, context, completedNodeIds,
                dispatchedNodeIds, status, pendingRequestNodeIds, pendingDecision, transitions, endedAt,
                terminalNodeId, terminalOutcome, sfdcNotified, failedNodeIds, dispatchAttempts,
                compensationQueue, compensationOf, null);
    }

    /** OPS P2 form: adds the per-node failure classes (enum names). */
    public static JourneyInstance restore(String journeyInstanceId, String correlationId, String journeyKey,
                                          int journeyVersion,
                                          String applicationRef, Map<String, Object> payload, Instant startedAt,
                                          long version,
                                          Map<String, Object> collectedResults, Map<String, Object> context,
                                          Set<String> completedNodeIds,
                                          Set<String> dispatchedNodeIds, InstanceStatus status,
                                          List<String> pendingRequestNodeIds, JourneyDecision pendingDecision,
                                          List<NodeTransition> transitions, Instant endedAt,
                                          String terminalNodeId, String terminalOutcome,
                                          NotifyState sfdcNotified,
                                          Set<String> failedNodeIds, Map<String, Integer> dispatchAttempts,
                                          List<String> compensationQueue, String compensationOf,
                                          Map<String, String> nodeFailureClasses) {
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
        if (failedNodeIds != null) {
            instance.failedNodeIds.addAll(failedNodeIds);
        }
        if (dispatchAttempts != null) {
            instance.dispatchAttempts.putAll(dispatchAttempts);
        }
        if (compensationQueue != null) {
            instance.compensationQueue.addAll(compensationQueue);
        }
        instance.compensationOf = compensationOf;
        if (nodeFailureClasses != null) {
            instance.nodeFailureClasses.putAll(nodeFailureClasses);
        }
        if (status != null) {
            instance.status = status;
        }
        if (pendingRequestNodeIds != null) {
            instance.pendingRequestNodeIds.addAll(pendingRequestNodeIds);
        }
        instance.pendingDecision = pendingDecision;
        if (transitions != null) {
            instance.transitions.addAll(transitions);
        }
        instance.endedAt = endedAt;
        instance.terminalNodeId = terminalNodeId;
        instance.terminalOutcome = terminalOutcome;
        // Legacy pre-ops records have no notify state: NONE, never a guess.
        instance.sfdcNotified = sfdcNotified == null ? NotifyState.NONE : sfdcNotified;
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
        recordTransition(nodeId, NodeTransition.Status.COMPLETED);
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
    public void markCompleted(String nodeId) {
        completedNodeIds.add(nodeId);
        recordTransition(nodeId, NodeTransition.Status.COMPLETED);
    }

    public void complete() { end(InstanceStatus.COMPLETED, terminalNodeId, terminalOutcome); }
    public void fail() { end(InstanceStatus.FAILED, terminalNodeId, terminalOutcome); }

    /** Terminal with detail (B.1): WHICH terminal ended the run, with WHICH outcome. */
    public void complete(String atTerminalNodeId, String outcome) {
        end(InstanceStatus.COMPLETED, atTerminalNodeId, outcome);
    }

    public void fail(String atTerminalNodeId, String outcome) {
        end(InstanceStatus.FAILED, atTerminalNodeId, outcome);
    }

    private void end(InstanceStatus terminalStatus, String atTerminalNodeId, String outcome) {
        this.status = terminalStatus;
        this.terminalNodeId = atTerminalNodeId;
        this.terminalOutcome = outcome;
        if (this.endedAt == null) {
            this.endedAt = Instant.now(); // first terminal event wins the end time
        }
    }

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

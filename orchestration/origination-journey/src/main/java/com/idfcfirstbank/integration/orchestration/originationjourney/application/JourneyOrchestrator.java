package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.EngineOutcome;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.OpsEvent;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.OpsEventPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Framework-free coordinator between the pure {@link JourneyEngine} and the OUT
 * ports. It starts a journey on an inbound origination event and advances it on
 * each capability response, persisting instance state between the async hops and
 * publishing the requests / final decision the engine produces.
 *
 * <p><b>Crash-safe hop protocol (persist-before-publish):</b> each hop first
 * SAVES the advanced state together with its publish INTENT (pending request node
 * ids + pending decision) under a compare-and-set, THEN publishes the side
 * effects (confirmed sends), then clears the intent with a follow-up save. Any
 * failure in between propagates, the triggering Kafka record is redelivered, the
 * duplicate guard sees the hop already applied, and only the still-pending
 * publishes are re-driven — a hop is never lost and never applied twice. A CAS
 * conflict (two replicas advancing the same journey concurrently) makes the
 * loser's redelivery reprocess from fresh state, so a parallel join always sees
 * both arms.
 */
public class JourneyOrchestrator {

    /**
     * How a T2 retry's DELAY is realized (in-JVM timer; a virtual thread pool in
     * production). The retry INTENT itself is durable — it sits in the
     * pending-publish outbox — so a crashed timer degrades to the next event
     * for this run re-driving it, or the liveness sweeper failing-with-notify
     * at budget. Never a silent loss.
     */
    @FunctionalInterface
    public interface RetryScheduler {
        void schedule(Runnable task, long delayMillis);

        /** Run inline (tests / no-delay wiring). */
        RetryScheduler IMMEDIATE = (task, delayMillis) -> task.run();
    }

    private static final Logger log = LoggerFactory.getLogger(JourneyOrchestrator.class);

    private final JourneyEngine engine;
    private final JourneyRegistry registry;
    private final JourneyInstanceStore store;
    private final CapabilityRequestPort capabilityRequestPort;
    private final DecisionOutboundPort decisionOutboundPort;
    private final Supplier<String> instanceIdSupplier;
    private final OpsEventPort opsEvents;
    private final RetryScheduler retryScheduler;

    public JourneyOrchestrator(JourneyEngine engine, JourneyRegistry registry, JourneyInstanceStore store,
                               CapabilityRequestPort capabilityRequestPort,
                               DecisionOutboundPort decisionOutboundPort,
                               Supplier<String> instanceIdSupplier) {
        this(engine, registry, store, capabilityRequestPort, decisionOutboundPort, instanceIdSupplier,
                OpsEventPort.NOOP);
    }

    public JourneyOrchestrator(JourneyEngine engine, JourneyRegistry registry, JourneyInstanceStore store,
                               CapabilityRequestPort capabilityRequestPort,
                               DecisionOutboundPort decisionOutboundPort,
                               Supplier<String> instanceIdSupplier,
                               OpsEventPort opsEvents) {
        this(engine, registry, store, capabilityRequestPort, decisionOutboundPort, instanceIdSupplier,
                opsEvents, RetryScheduler.IMMEDIATE);
    }

    public JourneyOrchestrator(JourneyEngine engine, JourneyRegistry registry, JourneyInstanceStore store,
                               CapabilityRequestPort capabilityRequestPort,
                               DecisionOutboundPort decisionOutboundPort,
                               Supplier<String> instanceIdSupplier,
                               OpsEventPort opsEvents,
                               RetryScheduler retryScheduler) {
        this.engine = engine;
        this.registry = registry;
        this.store = store;
        this.capabilityRequestPort = capabilityRequestPort;
        this.decisionOutboundPort = decisionOutboundPort;
        this.instanceIdSupplier = instanceIdSupplier;
        this.opsEvents = opsEvents;
        this.retryScheduler = retryScheduler;
    }

    /** Start a journey from a parsed inbound origination envelope. Returns the new instance id. */
    public String onOrigination(Map<String, Object> envelope) {
        String type = str(envelope.get("type"));
        String correlationId = firstNonNull(str(envelope.get("correlationId")),
                str(envelope.get("originalCorrelationId")), str(envelope.get("notificationId")));
        String applicationRef = firstNonNull(str(envelope.get("applicationRef")),
                str(envelope.get("notificationId")), "unknown");

        JourneyDefinition def = registry.resolveForType(type);

        // Deterministic instance id derived from the inbound origination's stable
        // key, so a redelivered origination (Kafka is at-least-once) maps to the
        // SAME instance id and is caught by the insert-if-absent gate below —
        // exactly-once start without a second random run.
        String dedupKey = firstNonNull(correlationId, str(envelope.get("notificationId")), applicationRef);
        String instanceId = dedupKey != null ? "ji-" + dedupKey : instanceIdSupplier.get();
        // The run PINS the definition version it starts on: every later hop
        // resolves this exact key@version, so a publish mid-run changes nothing
        // for this instance (A2 version pinning).
        JourneyInstance instance = new JourneyInstance(
                instanceId, correlationId, def.key(), def.version(), applicationRef, payloadOf(envelope));

        if (!store.insertIfAbsent(instance)) {
            // Exactly-once start is absolute: a lost insert gate ALWAYS drops, even
            // when the winner might still be mid-flight (resuming here would race a
            // live dispatch into a double start). The rare crash inside the winner's
            // insert→publish window is covered by the liveness sweeper, which FAILs
            // and notifies the channel — degraded to fail-with-notify, never silent
            // and never a second run.
            log.info("journey.start.duplicate instanceId={} — already started, dropping redelivery",
                    instanceId);
            return instanceId;
        }

        log.info("journey.start instanceId={} key={} version={} correlationId={} applicationRef={}",
                instanceId, def.key(), def.version(), correlationId, applicationRef);
        opsEvents.emit(OpsEvent.run(OpsEvent.RUN_STARTED, instance, null));
        dispatch(engine.start(def, instance), instance, def);
        return instanceId;
    }

    /** Advance a journey on a capability response. */
    public void onCapabilityResponse(CapabilityResponse response) {
        Optional<JourneyInstance> found = store.find(response.journeyInstanceId());
        if (found.isEmpty()) {
            log.warn("journey.response for unknown instanceId={} (capability={}, node={}) — dropped",
                    response.journeyInstanceId(), response.capabilityKey(), response.nodeId());
            return;
        }
        JourneyInstance instance = found.get();
        // Resolve the PINNED version this run started on — never the (possibly
        // newer) currently-published one.
        JourneyDefinition def = registry.definitionFor(instance.journeyKey(), instance.journeyVersion());

        // Duplicate / late-response guard: the hop was already applied (node done)
        // or the run is TERMINAL. Never re-run the engine for it — that would
        // re-fail a completed run or double-emit a decision. Just finish any
        // publishes the crashed attempt left pending, then drop. COMPENSATING is
        // a LIVE state: saga responses must flow through (T2).
        if (instance.isCompleted(response.nodeId()) || !instance.status().isLive()) {
            log.info("journey.response.duplicate instanceId={} node={} status={} — {}",
                    response.journeyInstanceId(), response.nodeId(), instance.status(),
                    instance.hasPendingPublishes() ? "re-driving pending publishes" : "dropped");
            if (instance.hasPendingPublishes()) {
                publishPending(def, instance);
            }
            return;
        }

        log.info("journey.response instanceId={} node={} capability={} status={}",
                response.journeyInstanceId(), response.nodeId(), response.capabilityKey(), response.status());
        // The node event narrates the response that CAUSES this hop, so it
        // precedes the run-terminal event dispatch() may emit (at-least-once:
        // a CAS-failed hop redelivers and may emit again; consumers tolerate).
        opsEvents.emit(OpsEvent.node(
                response.status() == CapabilityStatus.ERROR ? OpsEvent.NODE_FAILED : OpsEvent.NODE_COMPLETED,
                instance, response.nodeId()));
        dispatch(engine.onCapabilityResponse(def, instance, response), instance, def);
    }

    /**
     * The crash-safe hop: persist state + intent (CAS), THEN side effects, then
     * clear the intent. A CAS conflict on the first save propagates (the trigger
     * redelivers and reprocesses from fresh state); a conflict on the clear save
     * is benign — the newer writer loaded our saved intent and owns it.
     */
    private void dispatch(EngineOutcome outcome, JourneyInstance instance, JourneyDefinition def) {
        List<CapabilityRequest> requests = outcome.requests();
        List<EngineOutcome.RetryDirective> retries = outcome.retries();
        JourneyDecision decision = outcome.decision().orElse(null);

        if (requests.isEmpty() && retries.isEmpty() && decision == null) {
            store.save(instance); // pure state advance (e.g. one join arm done)
            emitRunEndedIfTerminal(instance);
            return;
        }

        // The outbox intent covers IMMEDIATE requests and scheduled RETRIES alike:
        // both are publishes this hop owes the world.
        List<String> pendingIds = new java.util.ArrayList<>(
                requests.stream().map(CapabilityRequest::nodeId).toList());
        retries.forEach(r -> pendingIds.add(r.nodeId()));
        instance.setPendingPublishes(pendingIds, decision);
        store.save(instance); // save #1: state + publish intent, BEFORE side effects

        for (CapabilityRequest request : requests) {
            log.info("journey.dispatch instanceId={} node={} capability={}",
                    instance.journeyInstanceId(), request.nodeId(), request.capabilityKey());
            capabilityRequestPort.publish(request);
            opsEvents.emit(OpsEvent.node(OpsEvent.NODE_DISPATCHED, instance, request.nodeId()));
        }
        if (decision != null) {
            publishDecision(instance, decision);
        }

        if (retries.isEmpty()) {
            instance.clearPendingPublishes();
        } else {
            // Confirmed publishes leave the outbox; the retry ids STAY pending
            // until the delayed re-drive publishes them.
            if (decision != null) {
                instance.markSfdcNotified();
            }
            instance.setPendingPublishes(
                    retries.stream().map(EngineOutcome.RetryDirective::nodeId).toList(), null);
        }
        saveClearedIntent(instance); // save #2: benign if a newer state won
        emitRunEndedIfTerminal(instance);

        for (EngineOutcome.RetryDirective retry : retries) {
            log.info("journey.retry.scheduled instanceId={} node={} delayMs={} attempt={}",
                    instance.journeyInstanceId(), retry.nodeId(), retry.delayMillis(),
                    instance.attemptsOf(retry.nodeId()));
            String instanceId = instance.journeyInstanceId();
            retryScheduler.schedule(() -> redrivePending(instanceId), retry.delayMillis());
        }
    }

    /**
     * Publish whatever the outbox still owes for this run (T2 delayed retries;
     * also safe for any crashed hop's leftovers). Exceptions are logged, never
     * thrown — the timer thread must survive; the sweeper is the net.
     */
    public void redrivePending(String instanceId) {
        try {
            Optional<JourneyInstance> found = store.find(instanceId);
            if (found.isEmpty() || !found.get().hasPendingPublishes()) {
                return;
            }
            JourneyInstance instance = found.get();
            JourneyDefinition def = registry.definitionFor(instance.journeyKey(), instance.journeyVersion());
            publishPending(def, instance);
        } catch (Exception e) {
            log.error("journey.retry.redrive failed for instanceId={} — sweeper will cover at budget",
                    instanceId, e);
        }
    }

    /** Re-drive the publishes a crashed/failed attempt persisted but never confirmed. */
    private void publishPending(JourneyDefinition def, JourneyInstance instance) {
        for (String nodeId : instance.pendingRequestNodeIds()) {
            CapabilityRequest request = engine.requestFor(def, instance, nodeId);
            log.info("journey.dispatch.redrive instanceId={} node={} capability={}",
                    instance.journeyInstanceId(), request.nodeId(), request.capabilityKey());
            capabilityRequestPort.publish(request);
        }
        if (instance.pendingDecision() != null) {
            publishDecision(instance, instance.pendingDecision());
        }
        instance.clearPendingPublishes();
        saveClearedIntent(instance);
    }

    /** run.completed / run.failed once the terminal hop is durable + published. */
    private void emitRunEndedIfTerminal(JourneyInstance instance) {
        if (instance.status() == InstanceStatus.COMPLETED) {
            opsEvents.emit(OpsEvent.run(OpsEvent.RUN_COMPLETED, instance, instance.terminalOutcome()));
        } else if (instance.status() == InstanceStatus.FAILED) {
            opsEvents.emit(OpsEvent.run(OpsEvent.RUN_FAILED, instance, instance.terminalOutcome()));
        }
    }

    private void publishDecision(JourneyInstance instance, JourneyDecision decision) {
        log.info("journey.decision instanceId={} outcome={} loanId={}",
                instance.journeyInstanceId(), decision.outcome(), decision.loanId());
        decisionOutboundPort.publish(decision);
    }

    private void saveClearedIntent(JourneyInstance instance) {
        try {
            store.save(instance);
        } catch (ConcurrentModificationException e) {
            // A newer state was saved after our intent save — that writer loaded
            // our pending intent and is responsible for it. Nothing to do.
            log.debug("journey.save.cleared-intent lost CAS for instanceId={} — newer state owns the intent",
                    instance.journeyInstanceId());
        }
    }

    private static final java.util.List<String> IDENTITY_FIELDS = java.util.List.of(
            "applicationRef", "type", "orgId", "correlationId", "notificationId", "sfdcRecordId",
            "source");

    private static Map<String, Object> payloadOf(Map<String, Object> envelope) {
        // Surface the envelope's identity fields so capabilities can read them
        // regardless of inline-payload vs S3 claim-check (applicationRef is always
        // present; the inline PAN may not be in the live path).
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        // Opaque business body FIRST: it is untrusted edge input (e.g. an SFDC Task
        // whose keys we do not control). Envelope IDENTITY fields are applied AFTER
        // and are authoritative, so a colliding body key (a payload carrying its own
        // "type"/"source"/"orgId") can NEVER shadow the platform's routing identity.
        Object inline = envelope.get("payload");
        if (inline instanceof Map<?, ?> m) {
            m.forEach((k, v) -> payload.put(String.valueOf(k), v));
        }
        for (String field : IDENTITY_FIELDS) {
            if (envelope.get(field) != null) {
                payload.put(field, envelope.get(field));
            }
        }
        return payload;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

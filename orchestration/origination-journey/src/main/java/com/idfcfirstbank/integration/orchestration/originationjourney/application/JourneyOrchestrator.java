package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.EngineOutcome;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Framework-free coordinator between the pure {@link JourneyEngine} and the OUT
 * ports. It starts a journey on an inbound origination event and advances it on
 * each capability response, persisting instance state between the async hops and
 * publishing the requests / final decision the engine produces.
 */
public class JourneyOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JourneyOrchestrator.class);

    private final JourneyEngine engine;
    private final JourneyRegistry registry;
    private final JourneyInstanceStore store;
    private final CapabilityRequestPort capabilityRequestPort;
    private final DecisionOutboundPort decisionOutboundPort;
    private final Supplier<String> instanceIdSupplier;

    public JourneyOrchestrator(JourneyEngine engine, JourneyRegistry registry, JourneyInstanceStore store,
                               CapabilityRequestPort capabilityRequestPort,
                               DecisionOutboundPort decisionOutboundPort,
                               Supplier<String> instanceIdSupplier) {
        this.engine = engine;
        this.registry = registry;
        this.store = store;
        this.capabilityRequestPort = capabilityRequestPort;
        this.decisionOutboundPort = decisionOutboundPort;
        this.instanceIdSupplier = instanceIdSupplier;
    }

    /** Start a journey from a parsed inbound origination envelope. Returns the new instance id. */
    public String onOrigination(Map<String, Object> envelope) {
        String type = str(envelope.get("type"));
        String correlationId = firstNonNull(str(envelope.get("correlationId")),
                str(envelope.get("originalCorrelationId")), str(envelope.get("notificationId")));
        String applicationRef = firstNonNull(str(envelope.get("applicationRef")),
                str(envelope.get("notificationId")), "unknown");

        JourneyDefinition def = registry.resolveForType(type);
        String instanceId = instanceIdSupplier.get();
        JourneyInstance instance = new JourneyInstance(
                instanceId, correlationId, def.key(), applicationRef, payloadOf(envelope));
        store.save(instance);

        log.info("journey.start instanceId={} key={} correlationId={} applicationRef={}",
                instanceId, def.key(), correlationId, applicationRef);
        dispatch(engine.start(def, instance), instance);
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
        JourneyDefinition def = registry.byKey(instance.journeyKey());
        log.info("journey.response instanceId={} node={} capability={} status={}",
                response.journeyInstanceId(), response.nodeId(), response.capabilityKey(), response.status());
        dispatch(engine.onCapabilityResponse(def, instance, response), instance);
    }

    private void dispatch(EngineOutcome outcome, JourneyInstance instance) {
        for (CapabilityRequest request : outcome.requests()) {
            log.info("journey.dispatch instanceId={} node={} capability={}",
                    instance.journeyInstanceId(), request.nodeId(), request.capabilityKey());
            capabilityRequestPort.publish(request);
        }
        outcome.decision().ifPresent(decision -> {
            log.info("journey.decision instanceId={} outcome={} loanId={}",
                    instance.journeyInstanceId(), decision.outcome(), decision.loanId());
            decisionOutboundPort.publish(decision);
        });
        store.save(instance);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> envelope) {
        Object payload = envelope.get("payload");
        if (payload instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        // No inline payload (e.g. S3 claim-check in prod): pass the envelope itself
        // so capabilities can still read identity fields (applicationRef, type, ...).
        return envelope;
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

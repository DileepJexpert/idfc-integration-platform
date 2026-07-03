package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the pure {@link JourneyEngine} decided to do for one step: zero or more
 * capability requests to publish, and — if the journey reached a terminal — the
 * final decision to push back. The Kafka adapters execute this; the engine stays
 * side-effect free and unit-testable.
 */
public final class EngineOutcome {

    /**
     * T2 retry directive: re-dispatch {@code nodeId}'s capability request after
     * {@code delayMillis}. The orchestrator persists the node id into the
     * pending-publish outbox in the SAME CAS save as the state advance, then
     * schedules the delayed re-drive — a crash in between leaves the intent
     * durable (re-driven by the next event for this run, or failed-with-notify
     * by the liveness sweeper at budget).
     */
    public record RetryDirective(String nodeId, long delayMillis) {
    }

    private final List<CapabilityRequest> requests = new ArrayList<>();
    private final List<RetryDirective> retries = new ArrayList<>();
    private JourneyDecision decision;

    void emit(CapabilityRequest request) {
        requests.add(request);
    }

    void retry(String nodeId, long delayMillis) {
        retries.add(new RetryDirective(nodeId, delayMillis));
    }

    void decide(JourneyDecision d) {
        this.decision = d;
    }

    public List<CapabilityRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RetryDirective> retries() {
        return List.copyOf(retries);
    }

    public Optional<JourneyDecision> decision() {
        return Optional.ofNullable(decision);
    }
}

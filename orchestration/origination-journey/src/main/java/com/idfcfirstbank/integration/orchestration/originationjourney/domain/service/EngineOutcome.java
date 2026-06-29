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

    private final List<CapabilityRequest> requests = new ArrayList<>();
    private JourneyDecision decision;

    void emit(CapabilityRequest request) {
        requests.add(request);
    }

    void decide(JourneyDecision d) {
        this.decision = d;
    }

    public List<CapabilityRequest> requests() {
        return List.copyOf(requests);
    }

    public Optional<JourneyDecision> decision() {
        return Optional.ofNullable(decision);
    }
}

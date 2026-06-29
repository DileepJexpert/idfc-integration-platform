package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.List;

/**
 * The final decision a completed (or failed) journey pushes back to the inbound
 * edge. {@code outcome} drives the SFDC callback; {@code loanId} is present only
 * when a booking node ran.
 */
public record JourneyDecision(
        String journeyInstanceId,
        String correlationId,
        String applicationRef,
        String outcome,
        String loanId,
        String terminalNodeId,
        List<String> emitted) {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String ERROR = "ERROR";

    public JourneyDecision {
        emitted = emitted == null ? List.of() : List.copyOf(emitted);
    }
}

package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.List;

/**
 * The final decision a completed (or failed) journey pushes back to the inbound
 * edge. {@code outcome} drives the SFDC callback; {@code loanId} is present only
 * when a booking node ran.
 *
 * <p>The decision carries the inbound edge's routing identity so the loop can be
 * closed over Kafka without a second contract: {@code source} tells each edge
 * whether the decision is theirs to act on (SFDC vs DIGITAL), and
 * {@code notificationId} is the key the SFDC edge CASes its idempotency record
 * on. Both are echoed from the {@code CanonicalEnvelope} that started the run.
 */
public record JourneyDecision(
        String journeyInstanceId,
        String correlationId,
        String applicationRef,
        String outcome,
        String loanId,
        String terminalNodeId,
        List<String> emitted,
        String source,
        String notificationId,
        String sfdcRecordId) {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String ERROR = "ERROR";

    public JourneyDecision {
        emitted = emitted == null ? List.of() : List.copyOf(emitted);
    }
}

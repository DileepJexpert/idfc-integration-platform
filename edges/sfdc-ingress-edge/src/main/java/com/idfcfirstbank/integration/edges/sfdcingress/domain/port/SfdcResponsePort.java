package com.idfcfirstbank.integration.edges.sfdcingress.domain.port;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;

/**
 * OUT port for pushing a decision back to SFDC (via Kong, later slice). Invoked
 * EXCLUSIVELY on the successful CAS transition INTO DECIDED (C1 ownership guard)
 * — resend reads never call this. {@code correlationId} is the trace of the call
 * that drove the transition and is threaded into the push-back.
 */
public interface SfdcResponsePort {
    void pushDecision(IdempotencyRecord decidedRecord, String correlationId);
}

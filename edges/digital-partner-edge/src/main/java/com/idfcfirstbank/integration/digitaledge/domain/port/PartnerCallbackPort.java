package com.idfcfirstbank.integration.digitaledge.domain.port;

/**
 * OUT port for the partner-side push-back — the digital mirror of the SFDC
 * record-update push-back. When the engine's decision returns, the edge notifies
 * the partner (callback URL from config; mocked in the demo).
 */
public interface PartnerCallbackPort {
    void pushDecision(String partner, String applicationRef, String outcome, String loanId, String correlationId);
}

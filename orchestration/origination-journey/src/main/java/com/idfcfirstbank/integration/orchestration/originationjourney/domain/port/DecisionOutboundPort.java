package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;

/**
 * OUT port: deliver a journey's final decision back toward the inbound edge.
 * The default adapter publishes it to a decision topic the edge consumes; a
 * mock/log adapter is used where no edge is wired.
 */
public interface DecisionOutboundPort {
    void publish(JourneyDecision decision);
}

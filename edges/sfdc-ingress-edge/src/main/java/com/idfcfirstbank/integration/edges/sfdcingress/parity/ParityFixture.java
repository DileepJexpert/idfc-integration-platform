package com.idfcfirstbank.integration.edges.sfdcingress.parity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A recorded Mule parity fixture: one captured input plus the expected outcome
 * (NOT a live Mule instance — Mule is not in docker-compose, §9/§F). The harness
 * feeds {@code request} through the edge and compares the result to {@code expected}.
 */
public record ParityFixture(String name, Request request, Expected expected) {

    public record Request(
            String notificationId,
            String correlationId,
            String sfdcRecordId,
            String applicationRef,
            String orgId,
            String type,
            JsonNode payload) {
    }

    /** Expected parity-relevant outcome (resolvedPayload is Mule's inlined body). */
    public record Expected(
            String dedupVerdict,
            String routingTopic,
            String downstreamJourney,
            JsonNode resolvedPayload) {
    }
}

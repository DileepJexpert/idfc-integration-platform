package com.idfcfirstbank.integration.shared.domain.capability;

import java.util.Map;

/**
 * THE CAPABILITY CONTRACT (request half). The orchestration engine emits one of
 * these onto {@code cap.<capabilityKey>.request.v1} for every task node it
 * executes; the capability consumes it, does its work, and replies with a
 * {@link CapabilityResponse} on {@code cap.<capabilityKey>.response.v1}.
 *
 * <p>This record is the authoritative wire shape — every capability implements
 * exactly this. Do NOT fork a near-identical copy per capability; depend on
 * {@code shared:shared-domain} and use this type.
 *
 * @param journeyInstanceId engine-assigned id correlating all messages of one run
 * @param correlationId     end-to-end trace id (carried from the inbound edge)
 * @param capabilityKey     the capability module key (e.g. {@code "scoring"})
 * @param nodeId            the DAG node this invocation corresponds to
 * @param payload           the run input (applicant identity, request fields, ...)
 * @param collectedResults  results of upstream nodes so far, keyed by node id;
 *                          e.g. the bureau node's result is visible to scoring
 */
public record CapabilityRequest(
        String journeyInstanceId,
        String correlationId,
        String capabilityKey,
        String nodeId,
        Map<String, Object> payload,
        Map<String, Object> collectedResults,
        /** Which capability operation to run (BRD §2, e.g. "register"). Null = the capability's single/default op. */
        String operation,
        /** Dedup key for exactly-once execution; null => the engine/dispatcher derives {@code journeyInstanceId:nodeId}. */
        String idempotencyKey) {

    /** Back-compat 6-arg form (no operation/idempotencyKey) — existing callers unchanged. */
    public CapabilityRequest(String journeyInstanceId, String correlationId, String capabilityKey,
                             String nodeId, Map<String, Object> payload, Map<String, Object> collectedResults) {
        this(journeyInstanceId, correlationId, capabilityKey, nodeId, payload, collectedResults, null, null);
    }
}

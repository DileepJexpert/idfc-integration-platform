package com.idfcfirstbank.integration.shared.domain.capability;

import java.util.Map;

/**
 * THE CAPABILITY CONTRACT (response half). A capability publishes this on
 * {@code cap.<capabilityKey>.response.v1} after handling a {@link CapabilityRequest}.
 * The engine correlates it by {@code journeyInstanceId} + {@code nodeId}, stores
 * {@link #result()} into the run's collected results, and advances the DAG.
 *
 * @param journeyInstanceId echoes the request's instance id
 * @param correlationId     echoes the request's trace id
 * @param nodeId            echoes the request's node id (how the engine routes it)
 * @param capabilityKey     echoes the request's capability key
 * @param status            {@link CapabilityStatus#OK} or {@link CapabilityStatus#ERROR}
 * @param result            capability output (e.g. {@code {"decision":"APPROVED"}});
 *                          empty/ignored when {@code status == ERROR}
 */
public record CapabilityResponse(
        String journeyInstanceId,
        String correlationId,
        String nodeId,
        String capabilityKey,
        CapabilityStatus status,
        Map<String, Object> result,
        /** Failure classification (BRD §2) — null on OK; TRANSIENT/PERMANENT on ERROR. */
        ErrorClass errorClass) {

    /** Back-compat 6-arg form (errorClass = null) — existing callers unchanged. */
    public CapabilityResponse(String journeyInstanceId, String correlationId, String nodeId,
                              String capabilityKey, CapabilityStatus status, Map<String, Object> result) {
        this(journeyInstanceId, correlationId, nodeId, capabilityKey, status, result, null);
    }

    /** OK result for a request, echoing its routing identity. */
    public static CapabilityResponse ok(CapabilityRequest req, Map<String, Object> output) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.OK, output, null);
    }

    /** ERROR result classified for the engine's retry policy. */
    public static CapabilityResponse error(CapabilityRequest req, ErrorClass errorClass) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.ERROR, java.util.Map.of(), errorClass);
    }
}

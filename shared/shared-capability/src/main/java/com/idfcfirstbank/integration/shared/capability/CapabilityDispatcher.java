package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The homogeneous capability core (framework-free of Kafka): resolve the request's
 * operation, execute it exactly once (idempotency store), and build a classified
 * {@link CapabilityResponse}. Every capability runs through THIS — no per-service
 * dispatch/idempotency/error handling.
 */
public final class CapabilityDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CapabilityDispatcher.class);

    private final Capability capability;
    private final Map<String, CapabilityOperation> operations = new LinkedHashMap<>();
    private final CapabilityIdempotencyStore idempotency;

    public CapabilityDispatcher(Capability capability, CapabilityIdempotencyStore idempotency) {
        this.capability = capability;
        this.idempotency = idempotency;
        for (CapabilityOperation op : capability.operations()) {
            operations.put(op.name(), op);
        }
    }

    /** Handle a request exactly once for its idempotency key, returning the response. */
    public CapabilityResponse handle(CapabilityRequest request) {
        String key = idempotencyKey(request);
        return idempotency.executeOnce(key, () -> dispatch(request));
    }

    private CapabilityResponse dispatch(CapabilityRequest request) {
        try {
            CapabilityOperation op = resolveOperation(request);
            Map<String, Object> output = op.execute(request);
            return CapabilityResponse.ok(request, output == null ? Map.of() : output);
        } catch (CapabilityException e) {
            log.warn("capability {} op {} failed ({}): {}", capability.key(),
                    request.operation(), e.errorClass(), e.getMessage());
            return CapabilityResponse.error(request, e.errorClass());
        } catch (Exception e) {
            // Unknown failure: do NOT retry by default (avoid retry storms on bugs);
            // capabilities must throw CapabilityException(TRANSIENT) for retryables.
            log.error("capability {} op {} errored", capability.key(), request.operation(), e);
            return CapabilityResponse.error(request, ErrorClass.PERMANENT);
        }
    }

    private CapabilityOperation resolveOperation(CapabilityRequest request) {
        String name = request.operation();
        if (name != null && operations.containsKey(name)) {
            return operations.get(name);
        }
        if (name == null && operations.size() == 1) {
            return operations.values().iterator().next(); // single-operation default
        }
        throw new CapabilityException(ErrorClass.PERMANENT,
                "unknown operation '" + name + "' for capability '" + capability.key() + "'");
    }

    private static String idempotencyKey(CapabilityRequest request) {
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
            return request.idempotencyKey();
        }
        return request.journeyInstanceId() + ":" + request.nodeId();
    }
}

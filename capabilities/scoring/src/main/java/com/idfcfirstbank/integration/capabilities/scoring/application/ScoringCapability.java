package com.idfcfirstbank.integration.capabilities.scoring.application;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The scoring {@link Capability} bean — the framework wires the Kafka shell AND
 * the idempotent dispatch around it, so a redelivered request (Kafka is
 * at-least-once) returns the first decision instead of re-deciding.
 *
 * <p>The single "decide" operation delegates to the untouched {@link ScoringService}.
 */
@Component
public class ScoringCapability implements Capability {

    private final ScoringService service;

    public ScoringCapability(ScoringService service) {
        this.service = service;
    }

    @Override
    public String key() {
        return "scoring";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(delegating("decide"));
    }

    private CapabilityOperation delegating(String name) {
        return new CapabilityOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> execute(CapabilityRequest request) {
                return unwrap(service.handle(request));
            }
        };
    }

    /** The service returns a contract response; the framework builds its own from output/throw. */
    private static Map<String, Object> unwrap(CapabilityResponse response) {
        if (response.status() == CapabilityStatus.OK) {
            return response.result();
        }
        throw new CapabilityException(
                response.errorClass() == null ? ErrorClass.PERMANENT : response.errorClass(),
                "scoring operation failed");
    }
}

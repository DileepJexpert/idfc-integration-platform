package com.idfcfirstbank.integration.capabilities.echo;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The trivial reference capability: one operation, {@code echo}, that returns the
 * request payload as output. It owns no state and calls no externals — its only
 * job is to prove the homogeneous framework end-to-end (engine-invokable contract
 * + idempotent dispatch) with zero plumbing in the app.
 */
@Component
public class EchoCapability implements Capability {

    @Override
    public String key() {
        return "echo";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(new CapabilityOperation() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public Map<String, Object> execute(CapabilityRequest request) {
                return Map.of("echo", request.payload() == null ? Map.of() : request.payload());
            }
        });
    }
}

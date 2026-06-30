package com.idfcfirstbank.integration.capabilities.mandate.application;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The mandate {@link Capability} bean — maps the §7 operations to {@link
 * MandateService} methods. The shared framework wires the Kafka shell + idempotent
 * dispatch around this; the app writes no plumbing.
 */
@Component
public class MandateCapability implements Capability {

    private final MandateService service;

    public MandateCapability(MandateService service) {
        this.service = service;
    }

    @Override
    public String key() {
        return "mandate";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(
                op("register", service::register),
                op("verifyEnach", service::verifyEnach),
                op("setupAutopayLink", service::setupAutopayLink),
                op("cancel", service::cancel),
                op("handleVendorCallback", service::handleVendorCallback));
    }

    private interface Fn {
        Map<String, Object> apply(CapabilityRequest req);
    }

    private static CapabilityOperation op(String name, Fn fn) {
        return new CapabilityOperation() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> execute(CapabilityRequest req) { return fn.apply(req); }
        };
    }
}

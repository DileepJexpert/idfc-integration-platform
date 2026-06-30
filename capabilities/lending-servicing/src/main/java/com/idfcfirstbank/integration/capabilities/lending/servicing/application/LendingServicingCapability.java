package com.idfcfirstbank.integration.capabilities.lending.servicing.application;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** The lending-servicing {@link Capability} bean — maps §7 operations to
 * {@link LendingServicingService} methods; the shared framework does the rest. */
@Component
public class LendingServicingCapability implements Capability {

    private final LendingServicingService service;

    public LendingServicingCapability(LendingServicingService service) {
        this.service = service;
    }

    @Override
    public String key() {
        return "lending-servicing";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(
                op("processMaturedLoan", service::processMaturedLoan),
                op("processClosedLoan", service::processClosedLoan),
                op("processExcessAmount", service::processExcessAmount),
                op("batchClosure", service::batchClosure),
                op("getMaruti", service::getMaruti));
    }

    private interface Fn { Map<String, Object> apply(CapabilityRequest req); }

    private static CapabilityOperation op(String name, Fn fn) {
        return new CapabilityOperation() {
            @Override public String name() { return name; }
            @Override public Map<String, Object> execute(CapabilityRequest req) { return fn.apply(req); }
        };
    }
}

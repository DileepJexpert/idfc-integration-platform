package com.idfcfirstbank.integration.capabilities.lending.origination.application;

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
 * The lending-origination {@link Capability} bean — the framework wires the Kafka
 * shell AND the idempotent dispatch around it. That idempotency is the point for
 * THIS capability above all others: the booking operation executes the FinnOne
 * stored procedure (SP_FINNONE_SUBMISSION), and a redelivered request (Kafka is
 * at-least-once) must return the FIRST booking's result — never book the loan a
 * second time.
 *
 * <p>Both §7 operations delegate to the untouched {@link LendingOriginationService},
 * which dispatches internally on {@code request.operation()}.
 */
@Component
public class LendingOriginationCapability implements Capability {

    private final LendingOriginationService service;

    public LendingOriginationCapability(LendingOriginationService service) {
        this.service = service;
    }

    @Override
    public String key() {
        return "lending-origination";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(delegating("book"), delegating("validateDeviceFinancing"));
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
                "lending-origination operation failed");
    }
}

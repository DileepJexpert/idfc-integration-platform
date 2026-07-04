package com.idfcfirstbank.integration.capabilities.fusionhcm;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DEMO — the mocked Fusion HCM capability, now over REAL HTTP.
 * {@code updateEmployee} is the per-record body of the file-batch demo: it POSTs
 * to Fusion; a well-formed record 200s and completes, a malformed one comes back
 * a real HTTP 400 → PERMANENT, so exactly that record's run fails while the rest
 * of the batch completes. {@code getEmployee} backs the sync-read reference.
 * Only the Fusion response DATA is mocked (on the mock-vendors server).
 */
@Component
public class FusionHcmCapability implements Capability {

    private final FusionVendor fusion;

    public FusionHcmCapability(FusionVendor fusion) {
        this.fusion = fusion;
    }

    @Override
    public String key() {
        return "fusion-hcm";
    }

    @Override
    public List<CapabilityOperation> operations() {
        return List.of(op("updateEmployee", this::updateEmployee),
                op("getEmployee", this::getEmployee));
    }

    private Map<String, Object> updateEmployee(CapabilityRequest request) {
        String employeeId = field(request, "employeeId");
        if (employeeId == null || employeeId.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "blank employeeId");
        }
        Map<String, Object> vendor =
                fusion.updateEmployee(employeeId, field(request, "lastWorkingDay"));
        return Map.of("updated", true, "employeeId", employeeId, "vendor", vendor);
    }

    private Map<String, Object> getEmployee(CapabilityRequest request) {
        String employeeId = field(request, "employeeId");
        if (employeeId == null || employeeId.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "blank employeeId");
        }
        return Map.of("employeeId", employeeId, "vendor", fusion.getEmployee(employeeId));
    }

    private static String field(CapabilityRequest request, String key) {
        Object v = request.payload() == null ? null : request.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static CapabilityOperation op(String name, Operation fn) {
        return new CapabilityOperation() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, Object> execute(CapabilityRequest request) {
                return fn.apply(request);
            }
        };
    }

    @FunctionalInterface
    private interface Operation {
        Map<String, Object> apply(CapabilityRequest request);
    }
}

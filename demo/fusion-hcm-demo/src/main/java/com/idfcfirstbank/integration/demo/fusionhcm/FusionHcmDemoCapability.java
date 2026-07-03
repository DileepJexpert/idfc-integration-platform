package com.idfcfirstbank.integration.demo.fusionhcm;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * DEMO — the mocked Fusion HCM capability. {@code updateEmployee} is the
 * per-record body of the file-batch demo: a well-formed record "updates"
 * (mock) and completes; a malformed one (blank id, unparseable date) is a
 * classified PERMANENT failure, so exactly that record's run fails while the
 * rest of the batch completes — the per-record error handling the legacy LWD
 * job got right, kept, with the platform's classes on top.
 * {@code getEmployee} exists only for the sync-read reference drawing.
 */
@Component
public class FusionHcmDemoCapability implements Capability {

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
        String lastWorkingDay = field(request, "lastWorkingDay");
        if (employeeId == null || employeeId.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "blank employeeId");
        }
        try {
            LocalDate.parse(lastWorkingDay == null ? "" : lastWorkingDay);
        } catch (DateTimeParseException e) {
            throw new CapabilityException(ErrorClass.PERMANENT,
                    "unparseable lastWorkingDay for employeeId=" + employeeId);
        }
        // Mocked Fusion accepts the update — ids only in the result.
        return Map.of("updated", true, "employeeId", employeeId,
                "lastWorkingDay", lastWorkingDay);
    }

    private Map<String, Object> getEmployee(CapabilityRequest request) {
        String employeeId = field(request, "employeeId");
        if (employeeId == null || employeeId.isBlank()) {
            throw new CapabilityException(ErrorClass.PERMANENT, "blank employeeId");
        }
        return Map.of("employeeId", employeeId, "status", "ACTIVE");
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

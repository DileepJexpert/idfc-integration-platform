package com.idfcfirstbank.integration.demo.fusionhcm;

import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-record body's LOGIC (the real HTTP flow is proven in
 * LegacyPatternsDemoIT): a good record updates; a blank id fails PERMANENT
 * before any call; the vendor's real 400 on a malformed date surfaces as
 * PERMANENT so exactly that record's run fails. The vendor is faked here to
 * stand in for the real HTTP response.
 */
class FusionHcmCapabilityTest {

    /** Fake Fusion: a malformed date "throws" the real-client's PERMANENT (a 400). */
    private static final FusionVendor FAKE = new FusionVendor() {
        @Override
        public Map<String, Object> updateEmployee(String employeeId, String lastWorkingDay) {
            if (!lastWorkingDay.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new CapabilityException(ErrorClass.PERMANENT, "fusion HTTP 400");
            }
            return Map.of("employeeId", employeeId, "status", "UPDATED");
        }

        @Override
        public Map<String, Object> getEmployee(String employeeId) {
            return Map.of("employeeId", employeeId, "status", "ACTIVE");
        }
    };

    private final FusionHcmDemoCapability capability = new FusionHcmDemoCapability(FAKE);

    private CapabilityOperation op(String name) {
        return capability.operations().stream()
                .filter(o -> o.name().equals(name)).findFirst().orElseThrow();
    }

    private static CapabilityRequest request(Map<String, Object> payload) {
        return new CapabilityRequest("ji-demo-2", "corr-demo-2", "fusion-hcm",
                "n_update", payload, Map.of(), "updateEmployee", "ji-demo-2:n_update");
    }

    @Test
    void wellFormedRecord_updates() throws Exception {
        assertThat(op("updateEmployee").execute(request(
                Map.of("employeeId", "EMP-001", "lastWorkingDay", "2026-07-31"))))
                .containsEntry("updated", true)
                .containsEntry("employeeId", "EMP-001");
    }

    @Test
    void malformedDate_surfacesVendor400AsPERMANENT_soThatRecordsRunFails() {
        assertThatThrownBy(() -> op("updateEmployee").execute(request(
                Map.of("employeeId", "EMP-004", "lastWorkingDay", "not-a-date"))))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void blankEmployeeId_failsPERMANENT_beforeAnyCall() {
        assertThatThrownBy(() -> op("updateEmployee").execute(request(
                Map.of("employeeId", " ", "lastWorkingDay", "2026-07-31"))))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }
}

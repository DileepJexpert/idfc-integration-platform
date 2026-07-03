package com.idfcfirstbank.integration.demo.fusionhcm;

import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The per-record body: good record updates; malformed record fails PERMANENT. */
class FusionHcmCapabilityTest {

    private final FusionHcmDemoCapability capability = new FusionHcmDemoCapability();

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
    void malformedDate_failsPERMANENT_soExactlyThatRecordsRunFails() {
        assertThatThrownBy(() -> op("updateEmployee").execute(request(
                Map.of("employeeId", "EMP-004", "lastWorkingDay", "not-a-date"))))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void blankEmployeeId_failsPERMANENT() {
        assertThatThrownBy(() -> op("updateEmployee").execute(request(
                Map.of("employeeId", " ", "lastWorkingDay", "2026-07-31"))))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }
}

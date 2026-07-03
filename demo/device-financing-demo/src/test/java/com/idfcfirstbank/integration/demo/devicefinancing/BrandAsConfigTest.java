package com.idfcfirstbank.integration.demo.devicefinancing;

import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The brand-as-config proof at the unit level: every behavioural difference in
 * this test comes from the ROWS built here — the capability code contains no
 * brand branching (verified by the HISENSE case: a brand the code has never
 * heard of works the moment a row exists, and FAILS CLOSED the moment it
 * doesn't).
 */
class BrandAsConfigTest {

    private static DeviceFinancingDemoProperties.BrandRow row(
            String auth, boolean validation, String passPath, String passValue,
            Map<String, Object> stub) {
        return new DeviceFinancingDemoProperties.BrandRow(
                auth, validation, passPath, passValue, stub);
    }

    private static final DeviceFinancingDemoProperties PROPS =
            new DeviceFinancingDemoProperties(
                    Map.of(
                            "SAMSUNG", row("OAUTH", true, "respCode", "0",
                                    Map.of("respCode", "0")),
                            "GODREJ", row("NA", false, "status", "OK",
                                    Map.of("status", "OK")),
                            "BOSCH", row("BAUTH", true, "result.code", "S",
                                    Map.of("result", Map.of("code", "S")))),
                    List.of("DEV-DECLINE"),
                    List.of("DEV-FAIL"));

    private final DeviceFinancingDemoCapability capability =
            new DeviceFinancingDemoCapability(PROPS);

    private static CapabilityRequest request(String operation, Map<String, Object> payload) {
        return new CapabilityRequest("ji-demo-1", "corr-demo-1", "device-financing",
                "n_test", payload, Map.of(), operation, "ji-demo-1:n_test");
    }

    private Map<String, Object> call(String operation, Map<String, Object> payload)
            throws Exception {
        for (CapabilityOperation op : capability.operations()) {
            if (op.name().equals(operation)) {
                return op.execute(request(operation, payload));
            }
        }
        throw new IllegalArgumentException("no op " + operation);
    }

    @Test
    void resolveBrand_returnsTheConfigRow_notCode() throws Exception {
        assertThat(call("resolveBrand", Map.of("brand", "SAMSUNG")))
                .containsEntry("validationRequired", true)
                .containsEntry("authType", "OAUTH");
        assertThat(call("resolveBrand", Map.of("brand", "GODREJ")))
                .containsEntry("validationRequired", false)
                .containsEntry("authType", "NA");
    }

    @Test
    void passLogicFieldPath_isPerBrandConfig_notBranching() throws Exception {
        // SAMSUNG passes on respCode == "0"; BOSCH on the NESTED result.code == "S".
        assertThat(call("block", Map.of("brand", "SAMSUNG", "deviceId", "DEV-1")))
                .containsEntry("approved", true);
        assertThat(call("block", Map.of("brand", "BOSCH", "deviceId", "DEV-2")))
                .containsEntry("approved", true);
    }

    @Test
    void declineDevice_isABusinessNo_notAnError() throws Exception {
        Map<String, Object> out =
                call("validate", Map.of("brand", "SAMSUNG", "deviceId", "DEV-DECLINE"));
        assertThat(out).containsEntry("approved", false);
    }

    @Test
    void failDevice_throwsClassifiedPERMANENT() {
        assertThatThrownBy(() ->
                call("block", Map.of("brand", "SAMSUNG", "deviceId", "DEV-FAIL")))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void unknownBrand_failsClosed_theLegacyFailOpenIsNotReproduced() {
        assertThatThrownBy(() -> call("resolveBrand", Map.of("brand", "HISENSE")))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void addingHisense_isARow_zeroCodeChange() throws Exception {
        // The "add a brand live" move: same capability CODE, one more row —
        // HISENSE passes on responseStatus == "-4" (the legacy oddball).
        var withHisense = new DeviceFinancingDemoProperties(
                Map.of(
                        "HISENSE", row("OAUTH", false, "responseStatus", "-4",
                                Map.of("responseStatus", "-4"))),
                List.of(), List.of());
        var capability2 = new DeviceFinancingDemoCapability(withHisense);
        for (CapabilityOperation op : capability2.operations()) {
            if (op.name().equals("block")) {
                assertThat(op.execute(request("block",
                        Map.of("brand", "HISENSE", "deviceId", "DEV-9"))))
                        .containsEntry("approved", true);
            }
        }
    }
}

package com.idfcfirstbank.integration.capabilities.devicefinancing;

import com.idfcfirstbank.integration.capabilities.devicefinancing.DeviceFinancingProperties.BrandRow;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The brand-as-config LOGIC at the unit level (the real HTTP flow is proven in
 * LegacyPatternsDemoIT against the mock-vendors server). Every behavioural
 * difference here comes from the ROWS built in this test — the capability code
 * contains no brand branching, verified by the HISENSE case: a brand the code
 * has never heard of works the moment a row exists, and FAILS CLOSED the moment
 * it doesn't. The vendor is a fake returning per-brand shapes (standing in for
 * the real HTTP response data).
 */
class BrandAsConfigTest {

    private static BrandRow row(String auth, boolean validation, String passPath, String passValue) {
        return new BrandRow(auth, validation, passPath, passValue, null, null, null, null);
    }

    private static final DeviceFinancingProperties PROPS =
            new DeviceFinancingProperties(
                    "http://unused", "http://unused", 3000, 10000,
                    Map.of(
                            // the REAL SFDC door carries brand in the svcName -> the row declares it
                            "APPLE", new BrandRow("OAUTH", false, "respCode", "0",
                                    null, null, null, "Post_Disbursal_Apple"),
                            "SAMSUNG", row("OAUTH", true, "respCode", "0"),
                            "GODREJ", row("NA", false, "status", "OK"),
                            "BOSCH", row("BAUTH", true, "result.code", "S")));

    /** Fake vendor: returns the brand's response SHAPE; DEV-DECLINE = non-pass. */
    private static final DeviceFinancingVendor FAKE_VENDOR =
            (operation, brand, deviceId, row) -> {
                boolean pass = !"DEV-DECLINE".equals(deviceId);
                return switch (brand) {
                    case "SAMSUNG", "APPLE" -> Map.of("respCode", pass ? "0" : "1");
                    case "BOSCH" -> Map.of("result", Map.of("code", pass ? "S" : "F"));
                    case "HISENSE" -> Map.of("responseStatus", pass ? "-4" : "-9");
                    default -> Map.of("status", pass ? "OK" : "DECLINED");
                };
            };

    private final DeviceFinancingCapability capability =
            new DeviceFinancingCapability(PROPS, FAKE_VENDOR);

    private static CapabilityRequest request(String operation, Map<String, Object> payload) {
        return new CapabilityRequest("ji-demo-1", "corr-demo-1", "device-financing",
                "n_test", payload, Map.of(), operation, "ji-demo-1:n_test");
    }

    private Map<String, Object> call(String operation, Map<String, Object> payload) {
        return call(capability, operation, payload);
    }

    @Test
    void resolveBrand_returnsTheConfigRow_notCode() {
        assertThat(call("resolveBrand", Map.of("brand", "SAMSUNG")))
                .containsEntry("validationRequired", true)
                .containsEntry("authType", "OAUTH");
        assertThat(call("resolveBrand", Map.of("brand", "GODREJ")))
                .containsEntry("validationRequired", false)
                .containsEntry("authType", "NA");
    }

    @Test
    void passLogicFieldPath_isPerBrandConfig_notBranching() {
        // SAMSUNG passes on respCode == "0"; BOSCH on the NESTED result.code == "S".
        assertThat(call("block", Map.of("brand", "SAMSUNG", "deviceId", "DEV-1")))
                .containsEntry("approved", true);
        assertThat(call("block", Map.of("brand", "BOSCH", "deviceId", "DEV-2")))
                .containsEntry("approved", true);
    }

    @Test
    void declineDevice_isABusinessNo_notAnError() {
        assertThat(call("validate", Map.of("brand", "SAMSUNG", "deviceId", "DEV-DECLINE")))
                .containsEntry("approved", false);
    }

    @Test
    void realSfdcApple_derivesBrandFromSvcName_andReadsImei() {
        // The real SFDC Post_Disbursal_Apple payload has NO brand and NO deviceId —
        // brand is implicit in the svcName (the envelope type), the device id is imei.
        Map<String, Object> payload = Map.of(
                "type", "Post_Disbursal_Apple",
                "imei", "431254356142345678",
                "paymentInfo", Map.of("swipeOrLoanAmount", "23800.00", "loanTenure", "12"));
        assertThat(call("resolveBrand", payload))
                .as("brand resolved from svcName -> the APPLE row (post-disbursal: validation off)")
                .containsEntry("validationRequired", false)
                .containsEntry("authType", "OAUTH");
        assertThat(call("block", payload))
                .as("APPLE pass-logic on the vendor response; imei stands in as the device id")
                .containsEntry("approved", true);
    }

    @Test
    void unmappedSvcName_withNoBrand_failsClosed() {
        assertThatThrownBy(() -> call("resolveBrand", Map.of("type", "Post_Disbursal_Nokia")))
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
    void addingHisense_isARow_zeroCodeChange() {
        var withHisense = new DeviceFinancingProperties(
                "http://unused", "http://unused", 3000, 10000,
                Map.of("HISENSE", row("OAUTH", false, "responseStatus", "-4")));
        var capability2 = new DeviceFinancingCapability(withHisense, FAKE_VENDOR);
        assertThat(call(capability2, "block", Map.of("brand", "HISENSE", "deviceId", "DEV-9")))
                .containsEntry("approved", true);
    }

    private Map<String, Object> call(DeviceFinancingCapability cap, String operation,
                                     Map<String, Object> payload) {
        for (CapabilityOperation op : cap.operations()) {
            if (op.name().equals(operation)) {
                try {
                    return op.execute(request(operation, payload));
                } catch (Exception e) {
                    throw (RuntimeException) e;
                }
            }
        }
        throw new IllegalArgumentException("no op " + operation);
    }
}

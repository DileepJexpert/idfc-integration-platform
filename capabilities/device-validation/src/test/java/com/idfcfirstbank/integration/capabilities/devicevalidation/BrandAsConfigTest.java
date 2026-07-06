package com.idfcfirstbank.integration.capabilities.devicevalidation;

import com.idfcfirstbank.integration.capabilities.devicevalidation.DeviceValidationProperties.BrandRow;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>Device VALIDATION runs three config-gated activities — validate / block /
 * unblock — each on the INTERSECTION of (the request's status asks for it) AND
 * (the brand's flag supports it). The device is identified by imei or serial per
 * the row's {@code validateBy}.
 */
class BrandAsConfigTest {

    /** A brand row: which activities it supports, how it identifies a device, its vendor contract. */
    private static BrandRow row(boolean validate, boolean block, boolean unblock,
                                String validateBy, String auth, String passPath, String passValue) {
        return new BrandRow(validate, block, unblock, validateBy, auth,
                passPath, passValue, null, null, null, null);
    }

    private static final DeviceValidationProperties PROPS =
            new DeviceValidationProperties(
                    "http://unused", "http://unused", 3000, 10000,
                    null, null, null,   // status-field/default-status/status-activities -> defaults
                    Map.of(
                            // the REAL SFDC door carries brand in the svcName -> the row declares it
                            "APPLE", new BrandRow(false, true, false, "imei", "OAUTH",
                                    "respCode", "0", null, null, null, "Post_Disbursal_Apple"),
                            // full lifecycle, imei-identified
                            "SAMSUNG", row(true, true, true, "imei", "OAUTH", "respCode", "0"),
                            // appliance: serial-identified, block-only, no auth
                            "GODREJ", row(false, true, false, "serial", "NA", "status", "OK"),
                            // appliance: serial-identified, full lifecycle, nested pass path
                            "BOSCH", row(true, true, true, "serial", "BAUTH", "result.code", "S")));

    /** Fake vendor: returns the brand's response SHAPE; a device id carrying DECLINE = non-pass. */
    private static final DeviceValidationVendor FAKE_VENDOR =
            (operation, brand, deviceId, row) -> deviceShape(brand, !String.valueOf(deviceId).contains("DECLINE"));

    private static Map<String, Object> deviceShape(String brand, boolean pass) {
        return switch (brand) {
            case "SAMSUNG", "APPLE" -> Map.of("respCode", pass ? "0" : "1");
            case "BOSCH" -> Map.of("result", Map.of("code", pass ? "S" : "F"));
            case "HISENSE" -> Map.of("responseStatus", pass ? "-4" : "-9");
            default -> Map.of("status", pass ? "OK" : "DECLINED");
        };
    }

    private final DeviceValidationCapability capability =
            new DeviceValidationCapability(PROPS, FAKE_VENDOR);

    private static CapabilityRequest request(String operation, Map<String, Object> payload) {
        return new CapabilityRequest("ji-demo-1", "corr-demo-1", "device-validation",
                "n_test", payload, Map.of(), operation, "ji-demo-1:n_test");
    }

    private Map<String, Object> call(String operation, Map<String, Object> payload) {
        return call(capability, operation, payload);
    }

    // ---- the activity plan: intersection of (request asks) AND (brand supports) ----

    @Test
    void decideActivities_status1_intersectsRequestWithBrandFlags() {
        // status "1" (default) asks for validate + block.
        // SAMSUNG supports both -> both run. GODREJ has validate off -> block only.
        assertThat(call("decideActivities", Map.of("brand", "SAMSUNG")))
                .containsEntry("runValidate", true)
                .containsEntry("runBlock", true)
                .containsEntry("runUnblock", false)
                .containsEntry("validateBy", "imei")
                .containsEntry("authType", "OAUTH");
        assertThat(call("decideActivities", Map.of("brand", "GODREJ")))
                .as("GODREJ validate flag is off -> validate is skipped even though status asks for it")
                .containsEntry("runValidate", false)
                .containsEntry("runBlock", true)
                .containsEntry("runUnblock", false)
                .containsEntry("validateBy", "serial")
                .containsEntry("authType", "NA");
    }

    @Test
    void decideActivities_status2_asksUnblock_gatedByBrandFlag() {
        // status "2" asks for unblock only. SAMSUNG supports it; GODREJ does not.
        assertThat(call("decideActivities", Map.of("brand", "SAMSUNG", "status", "2")))
                .containsEntry("runValidate", false)
                .containsEntry("runBlock", false)
                .containsEntry("runUnblock", true);
        assertThat(call("decideActivities", Map.of("brand", "GODREJ", "status", "2")))
                .as("GODREJ unblock flag is off -> nothing runs (empty intersection)")
                .containsEntry("runValidate", false)
                .containsEntry("runBlock", false)
                .containsEntry("runUnblock", false);
    }

    // ---- the vendor activities: pass-logic is per-brand config, decline is a business no ----

    @Test
    void passLogicFieldPath_isPerBrandConfig_notBranching() {
        // SAMSUNG passes on respCode == "0"; BOSCH on the NESTED result.code == "S".
        assertThat(call("block", Map.of("brand", "SAMSUNG", "deviceId", "DEV-1")))
                .containsEntry("valid", true);
        assertThat(call("block", Map.of("brand", "BOSCH", "deviceId", "DEV-2")))
                .containsEntry("valid", true);
    }

    @Test
    void unblock_isTheSameParameterizedVendorCall() {
        // unblock reuses the per-brand pass-logic — SAMSUNG unblock passes on respCode == "0".
        assertThat(call("unblock", Map.of("brand", "SAMSUNG", "deviceId", "DEV-1")))
                .containsEntry("valid", true);
    }

    @Test
    void declineDevice_isABusinessNo_notAnError() {
        assertThat(call("validate", Map.of("brand", "SAMSUNG", "deviceId", "DEV-DECLINE")))
                .containsEntry("valid", false);
    }

    // ---- validateBy: imei-brands read imei, serial-brands read serial ----

    @Test
    void validateBy_picksTheRightIdentifierField() {
        List<String> seen = new ArrayList<>();
        DeviceValidationVendor capturing = (operation, brand, deviceId, row) -> {
            seen.add(deviceId);
            return deviceShape(brand, true);
        };
        var cap = new DeviceValidationCapability(PROPS, capturing);

        // BOSCH is validateBy=serial -> the vendor is called with the SERIAL, not the decoy imei.
        call(cap, "block", Map.of("brand", "BOSCH", "imei", "IMEI-DECOY", "serial", "SER-7"));
        // SAMSUNG is validateBy=imei -> the vendor is called with the IMEI, not the decoy serial.
        call(cap, "block", Map.of("brand", "SAMSUNG", "imei", "IMEI-9", "serial", "SER-DECOY"));

        assertThat(seen).containsExactly("SER-7", "IMEI-9");
    }

    // ---- the REAL SFDC Apple door: brand from svcName, imei not deviceId ----

    @Test
    void realSfdcApple_derivesBrandFromSvcName_andReadsImei() {
        // The real SFDC Post_Disbursal_Apple payload has NO brand and NO deviceId —
        // brand is implicit in the svcName (the envelope type), the device id is imei,
        // and there is no status field (a post-disbursal notification defaults to "1").
        Map<String, Object> payload = Map.of(
                "type", "Post_Disbursal_Apple",
                "imei", "431254356142345678",
                "paymentInfo", Map.of("swipeOrLoanAmount", "23800.00", "loanTenure", "12"));
        assertThat(call("decideActivities", payload))
                .as("brand resolved from svcName -> the APPLE row (validate off, block on)")
                .containsEntry("runValidate", false)
                .containsEntry("runBlock", true)
                .containsEntry("runUnblock", false)
                .containsEntry("authType", "OAUTH");
        assertThat(call("block", payload))
                .as("APPLE pass-logic on the vendor response; imei stands in as the device id")
                .containsEntry("valid", true);
    }

    @Test
    void unmappedSvcName_withNoBrand_failsClosed() {
        assertThatThrownBy(() -> call("decideActivities", Map.of("type", "Post_Disbursal_Nokia")))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void unknownBrand_failsClosed_theLegacyFailOpenIsNotReproduced() {
        assertThatThrownBy(() -> call("decideActivities", Map.of("brand", "HISENSE")))
                .isInstanceOfSatisfying(CapabilityException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }

    @Test
    void addingHisense_isARow_zeroCodeChange() {
        var withHisense = new DeviceValidationProperties(
                "http://unused", "http://unused", 3000, 10000, null, null, null,
                Map.of("HISENSE", row(false, true, false, "imei", "OAUTH", "responseStatus", "-4")));
        var capability2 = new DeviceValidationCapability(withHisense, FAKE_VENDOR);
        assertThat(call(capability2, "block", Map.of("brand", "HISENSE", "deviceId", "DEV-9")))
                .containsEntry("valid", true);
    }

    private Map<String, Object> call(DeviceValidationCapability cap, String operation,
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

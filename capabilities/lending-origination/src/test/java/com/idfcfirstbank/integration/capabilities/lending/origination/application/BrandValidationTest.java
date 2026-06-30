package com.idfcfirstbank.integration.capabilities.lending.origination.application;

import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.brand.MockBrandValidationAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.MockFinnOneAdapter;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** BRD §5: the config-driven device-financing validation, dispatched by operation
 * inside lending-origination. Pass/fail comes from brand-config/samsung-upgrade.json. */
class BrandValidationTest {

    private final LendingOriginationService service =
            new LendingOriginationService(new MockFinnOneAdapter(), new MockBrandValidationAdapter());

    private static CapabilityRequest validate(Map<String, Object> payload) {
        return new CapabilityRequest("ji", "corr", "lending-origination", "n_brand",
                payload, Map.of(), "validateDeviceFinancing", null);
    }

    @Test
    void samsungWithStatusZeroPasses() {
        CapabilityResponse r = service.handle(validate(Map.of(
                "brand", "samsung-upgrade", "devicePayload", Map.of("Status", "0"))));
        assertThat(r.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(r.result()).containsEntry("pass", "Y").containsEntry("brand", "SAMSUNG");
    }

    @Test
    void samsungWithNonZeroStatusFails() {
        CapabilityResponse r = service.handle(validate(Map.of(
                "brand", "samsung-upgrade", "devicePayload", Map.of("Status", "9"))));
        assertThat(r.result()).containsEntry("pass", "N");
    }

    @Test
    void unknownBrandIsAnError() {
        CapabilityResponse r = service.handle(validate(Map.of(
                "brand", "nokia-9000", "devicePayload", Map.of("Status", "0"))));
        assertThat(r.status()).isEqualTo(CapabilityStatus.ERROR);
    }

    @Test
    void bookingPathStillWorks() {
        CapabilityResponse r = service.handle(new CapabilityRequest(
                "ji", "corr", "lending-origination", "n_book", Map.of("applicationRef", "APP-1"),
                Map.of(), "book", null));
        assertThat(r.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(r.result()).containsKey("loanId");
    }
}

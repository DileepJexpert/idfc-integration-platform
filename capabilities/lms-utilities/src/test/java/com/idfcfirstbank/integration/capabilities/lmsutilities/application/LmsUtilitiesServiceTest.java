package com.idfcfirstbank.integration.capabilities.lmsutilities.application;

import com.idfcfirstbank.integration.capabilities.lmsutilities.config.LmsUtilitiesProperties;
import com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out.LmsUtilityPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The requestCode-dispatch + house-envelope-mapping policy at the unit level (the
 * real HTTP path is proven end-to-end elsewhere). The port is a capturing fake so the
 * fail-closed "unknown requestCode never reaches the backend" is asserted on the call
 * count.
 */
class LmsUtilitiesServiceTest {

    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "SAVEIN");

    /** knownRequestCodes = [OFFER_CHECK] — BALANCE_CHECK is deliberately NOT allow-listed. */
    private static LmsUtilitiesProperties props() {
        return new LmsUtilitiesProperties("http://vendor.local", null, 3000, 10000, List.of("OFFER_CHECK"));
    }

    private static Map<String, Object> offerPayload() {
        return Map.of(
                "entityName", "PBLINE",
                "agreementId", "pbline_5eb1",
                "crnNo", "pbline_5eb1",
                "requestCode", "OFFER_CHECK");
    }

    /** A capturing fake vendor: records the last request + call count, returns a configured body or throws. */
    private static final class FakeLmsPort implements LmsUtilityPort {
        int calls;
        Map<String, Object> lastRequest;
        Map<String, Object> response;
        RuntimeException toThrow;

        @Override
        public Map<String, Object> call(Map<String, Object> requestBody) {
            calls++;
            lastRequest = requestBody;
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    /** A raw IDFC house envelope { metadata{…}, resource_data[…] }, as the backend returns it. */
    private static Map<String, Object> houseEnvelope(String status, String message, List<Map<String, Object>> rows) {
        return Map.of(
                "metadata", Map.of("status", status, "message", message, "version", "v1", "time", "2026-07-06T10:21:06"),
                "resource_data", rows);
    }

    @Test
    void offerCheck_success_returnsMappedOfferRow() {
        FakeLmsPort port = new FakeLmsPort();
        port.response = houseEnvelope("SUCCESS", "OFFER_CHECK processed successfully",
                List.of(Map.of(
                        "EXPIRED_DATE", "2030-10-03T06:57:03",
                        "LOAN_AMOUNT", "500000",
                        "REQID", "pbline_5eb1",
                        "RISK_SEGMENT", "LOW RISK",
                        "ROI", "14")));
        var service = new LmsUtilitiesService(port, props());

        Map<String, Object> body = service.invoke("OFFER_CHECK", offerPayload(), CTX);

        assertThat(body.get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resourceData = (List<Map<String, Object>>) body.get("resourceData");
        assertThat(resourceData).hasSize(1);
        assertThat(resourceData.get(0))
                .containsEntry("LOAN_AMOUNT", "500000")
                .containsEntry("ROI", "14");
        assertThat(port.calls).as("the backend is called once for a known requestCode").isEqualTo(1);
    }

    @Test
    void noOffer_successWithEmptyResourceData_isCleanEmpty_notAnError() {
        FakeLmsPort port = new FakeLmsPort();
        port.response = houseEnvelope("SUCCESS", "OFFER_CHECK processed successfully", List.of());
        var service = new LmsUtilitiesService(port, props());

        Map<String, Object> body = service.invoke("OFFER_CHECK", offerPayload(), CTX);

        assertThat(body.get("status")).isEqualTo("SUCCESS");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resourceData = (List<Map<String, Object>>) body.get("resourceData");
        assertThat(resourceData)
                .as("an empty resource_data on a SUCCESS is a clean 'no offer', NOT an error")
                .isEmpty();
    }

    @Test
    void unknownRequestCode_failsClosed_andNeverCallsBackend() {
        FakeLmsPort port = new FakeLmsPort();
        var service = new LmsUtilitiesService(port, props());   // BALANCE_CHECK is not in knownRequestCodes

        assertThatThrownBy(() -> service.invoke("BALANCE_CHECK", offerPayload(), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT);
                    assertThat(e.code()).isEqualTo("UNKNOWN_REQUEST_CODE");
                });
        assertThat(port.calls).as("an unknown requestCode is refused BEFORE any backend call").isZero();
    }

    @Test
    void technicalFailure_propagates() {
        FakeLmsPort port = new FakeLmsPort();
        port.toThrow = new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "LMS backend unreachable");
        var service = new LmsUtilitiesService(port, props());

        assertThatThrownBy(() -> service.invoke("OFFER_CHECK", offerPayload(), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.TRANSIENT));
    }
}

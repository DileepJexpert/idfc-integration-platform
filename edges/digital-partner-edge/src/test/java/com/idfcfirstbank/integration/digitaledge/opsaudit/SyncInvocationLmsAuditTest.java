package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.capabilities.lmsutilities.application.LmsUtilitiesService;
import com.idfcfirstbank.integration.capabilities.lmsutilities.config.LmsUtilitiesProperties;
import com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out.LmsUtilityPort;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit outcome RULE for a pure-READ capability (lms-utilities), over the real
 * invoker path. An offer and a "no offer" are BOTH {@code SUCCESS} — a query that
 * correctly returns "none" is not a failure (the same logic as a valid decline not
 * being an error). An unknown requestCode is a fail-closed {@code TECHNICAL_ERROR}.
 * lms has no dedup key (it is a read, not a money movement), so records carry none.
 */
class SyncInvocationLmsAuditTest {

    private InMemorySyncInvocationStore store;
    private SyncInvocationQueryService query;
    private FakeLmsPort port;
    private SyncCapabilityInvoker invoker;

    private final SyncRequestContext ctx = SyncRequestContext.of("corr-lms-1", null, "SAVEIN");

    @BeforeEach
    void setUp() {
        store = new InMemorySyncInvocationStore();
        query = new SyncInvocationQueryService(store);
        port = new FakeLmsPort();
        LmsUtilitiesProperties props =
                new LmsUtilitiesProperties("http://vendor.local", null, 3000, 10000, List.of("OFFER_CHECK"));
        LmsUtilitiesService lms = new LmsUtilitiesService(port, props);
        invoker = new SyncCapabilityInvoker(List.of(lms), new SyncInvocationRecorderAdapter(store));
    }

    @Test
    void offer_records_SUCCESS_with_no_idempotencyKey() {
        port.response = houseEnvelope("SUCCESS", List.of(Map.of("LOAN_AMOUNT", "500000", "ROI", "14")));

        invoker.invoke("lms-utilities", "OFFER_CHECK", offerPayload("crn-1"), ctx);

        SyncInvocation rec = only();
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(rec.capabilityKey()).isEqualTo("lms-utilities");
        assertThat(rec.operation()).isEqualTo("OFFER_CHECK");
        assertThat(rec.source()).isEqualTo("SAVEIN");
        assertThat(rec.idempotencyKey()).as("a read has no dedup key").isNull();
        assertThat(rec.transactionId()).isNull();
        assertThat(rec.deduped()).isFalse();
    }

    @Test
    void no_offer_is_recorded_as_SUCCESS_not_BUSINESS_FAILURE() {
        // SUCCESS with an EMPTY resource_data — the customer simply has no pre-approved offer.
        port.response = houseEnvelope("SUCCESS", List.of());

        invoker.invoke("lms-utilities", "OFFER_CHECK", offerPayload("NO-OFFER-CRN"), ctx);

        assertThat(only().outcome())
                .as("a query that correctly returns 'no offer' is a SUCCESS, never a failure")
                .isEqualTo(SyncOutcome.SUCCESS);
    }

    @Test
    void unknown_requestCode_fails_closed_as_TECHNICAL_ERROR() {
        assertThatThrownBy(() -> invoker.invoke("lms-utilities", "BALANCE_CHECK", offerPayload("crn-2"), ctx))
                .isInstanceOf(SyncTechnicalException.class);

        SyncInvocation rec = only();
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.TECHNICAL_ERROR);
        assertThat(rec.errorClass()).isEqualTo("PERMANENT");
        assertThat(rec.errorCode()).isEqualTo("UNKNOWN_REQUEST_CODE");
        assertThat(port.calls).as("fail-closed: the backend is never called").isEqualTo(0);
    }

    // --- helpers / fakes -------------------------------------------------------------

    private SyncInvocation only() {
        List<SyncInvocation> all = query.list(
                new SyncInvocationQueryService.Filter(null, null, null, null, null), 0, 50).items();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private static Map<String, Object> offerPayload(String crn) {
        return Map.of("entityName", "PBLINE", "agreementId", "agr-1", "crnNo", crn, "requestCode", "OFFER_CHECK");
    }

    private static Map<String, Object> houseEnvelope(String status, List<Map<String, Object>> rows) {
        return Map.of(
                "metadata", Map.of("status", status, "message", "ok", "version", "v1", "time", "2026-07-07T10:00:00"),
                "resource_data", rows);
    }

    static final class FakeLmsPort implements LmsUtilityPort {
        int calls;
        Map<String, Object> response;

        @Override
        public Map<String, Object> call(Map<String, Object> requestBody) {
            calls++;
            return response;
        }
    }
}

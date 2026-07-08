package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.application.ImpsDisbursalService;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtRequest;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsFtPort;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsIdempotencyStorePort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The sync-lane audit, exercised over the REAL invoker path: SyncCapabilityInvoker →
 * the real ImpsDisbursalService (fake vendor port + fake idempotency store) →
 * SyncInvocationRecorderAdapter → InMemorySyncInvocationStore, read back through the
 * real SyncInvocationQueryService. No Spring, no external deps.
 */
class SyncInvocationAuditTest {

    private InMemorySyncInvocationStore store;
    private SyncInvocationQueryService query;
    private FakeImpsFtPort port;
    private SyncCapabilityInvoker invoker;

    private final SyncRequestContext ctx = SyncRequestContext.of("corr-1", "txn-hdr-1", "INDMONEY");

    @BeforeEach
    void setUp() {
        store = new InMemorySyncInvocationStore();
        query = new SyncInvocationQueryService(store);
        port = new FakeImpsFtPort();
        ImpsDisbursalService imps = new ImpsDisbursalService(port, new FakeIdemStore());
        invoker = new SyncCapabilityInvoker(List.of(imps), new SyncInvocationRecorderAdapter(store));
    }

    @Test
    void success_writes_one_SUCCESS_record_with_transactionId() {
        port.behavior = r -> new ImpsFtResult(r.reqId(), "S", "TXN-1", r.custBankAccNo(), "Bene AC", "", "");

        invoker.invoke("imps-disbursal", "transfer", payload("idem-1", "2026040915306622"), ctx);

        List<SyncInvocation> all = query.list(anyFilter(), 0, 50).items();
        assertThat(all).hasSize(1);
        SyncInvocation rec = all.get(0);
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(rec.transactionId()).isEqualTo("TXN-1");
        assertThat(rec.capabilityKey()).isEqualTo("imps-disbursal");
        assertThat(rec.operation()).isEqualTo("transfer");
        assertThat(rec.idempotencyKey()).isEqualTo("idem-1");
        assertThat(rec.source()).isEqualTo("INDMONEY");
        assertThat(rec.correlationId()).isEqualTo("corr-1");
        assertThat(rec.deduped()).isFalse();
        assertThat(rec.invocationId()).startsWith("sync-");
    }

    @Test
    void business_no_writes_BUSINESS_FAILURE_not_TECHNICAL() {
        port.behavior = r -> new ImpsFtResult(r.reqId(), "F", null, r.custBankAccNo(), null, "E01", "invalid beneficiary");

        invoker.invoke("imps-disbursal", "transfer", payload("idem-bad", "BAD-ACCOUNT"), ctx);

        SyncInvocation rec = query.list(anyFilter(), 0, 50).items().get(0);
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.BUSINESS_FAILURE);
        assertThat(rec.transactionId()).isNull();
    }

    @Test
    void technical_failure_still_records_and_rethrows() {
        port.behavior = r -> {
            throw new SyncTechnicalException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "vendor timed out");
        };

        assertThatThrownBy(() ->
                invoker.invoke("imps-disbursal", "transfer", payload("idem-slow", "SLOW-ACC"), ctx))
                .isInstanceOf(SyncTechnicalException.class);

        SyncInvocation rec = query.list(anyFilter(), 0, 50).items().get(0);
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.TECHNICAL_ERROR);
        assertThat(rec.errorClass()).isEqualTo("AMBIGUOUS");
        assertThat(rec.errorCode()).isEqualTo("READ_TIMEOUT");
        assertThat(rec.transactionId()).isNull();
    }

    @Test
    void duplicate_idempotencyKey_records_a_dedup_and_does_not_re_transfer() {
        port.behavior = r -> new ImpsFtResult(r.reqId(), "S", "TXN-9", r.custBankAccNo(), "Bene AC", "", "");

        invoker.invoke("imps-disbursal", "transfer", payload("idem-dup", "2026040915306622"), ctx);
        invoker.invoke("imps-disbursal", "transfer", payload("idem-dup", "2026040915306622"), ctx);

        assertThat(port.calls).isEqualTo(1); // idempotent — no second transfer reached the vendor

        List<SyncInvocation> byKey = query.byIdempotencyKey("idem-dup");
        assertThat(byKey).hasSize(2);
        assertThat(byKey.stream().filter(r -> !r.deduped()).count()).isEqualTo(1); // one real transfer record
        assertThat(byKey.stream().filter(SyncInvocation::deduped).count()).isEqualTo(1); // one dedup replay record
    }

    // --- helpers / fakes -------------------------------------------------------------

    private static SyncInvocationQueryService.Filter anyFilter() {
        return new SyncInvocationQueryService.Filter(null, null, null, null, null);
    }

    private static Map<String, Object> payload(String idempotentId, String acc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("custBankAccNo", acc);
        m.put("idempotentId", idempotentId);
        m.put("ifscCode", "UTIB0000001");
        m.put("reqId", "REQ-" + idempotentId);
        m.put("source", "INDMONEY");
        m.put("loanNo", "LN-1");
        m.put("isDisbursalFlag", "Y");
        return m;
    }

    static final class FakeImpsFtPort implements ImpsFtPort {
        Function<ImpsFtRequest, ImpsFtResult> behavior =
                r -> new ImpsFtResult(r.reqId(), "S", "TXN", r.custBankAccNo(), "Bene", "", "");
        int calls = 0;

        @Override
        public ImpsFtResult transfer(ImpsFtRequest request) {
            calls++;
            return behavior.apply(request);
        }
    }

    static final class FakeIdemStore implements ImpsIdempotencyStorePort {
        private final Map<String, ImpsFtResult> m = new HashMap<>();

        @Override
        public Optional<ImpsFtResult> find(String idempotentId) {
            return Optional.ofNullable(m.get(idempotentId));
        }

        @Override
        public void save(String idempotentId, ImpsFtResult result) {
            m.put(idempotentId, result);
        }
    }
}

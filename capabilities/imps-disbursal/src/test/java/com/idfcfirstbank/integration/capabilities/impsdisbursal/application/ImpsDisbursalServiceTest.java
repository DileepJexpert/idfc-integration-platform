package com.idfcfirstbank.integration.capabilities.impsdisbursal.application;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.out.idempotency.InMemoryImpsIdempotencyStore;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtRequest;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsFtPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money-movement idempotency + outcome-classification policy at the unit level
 * (the real HTTP path is proven end-to-end in ImpsDisbursalSyncIT). The vendor is a
 * counting fake so "single transfer on repeat" is asserted on the call count.
 */
class ImpsDisbursalServiceTest {

    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "INDMONEY");

    private static ImpsFtRequest request(String idempotentId, String acc) {
        return new ImpsFtRequest(acc, "UTIB0000001", "REQ-1", "110855952", "Y", idempotentId, "INDMONEY");
    }

    /** A counting fake vendor whose response/behaviour is set per test. */
    private static final class FakeImpsFt implements ImpsFtPort {
        final AtomicInteger calls = new AtomicInteger();
        java.util.function.Function<ImpsFtRequest, ImpsFtResult> behaviour;

        @Override
        public ImpsFtResult transfer(ImpsFtRequest request) {
            calls.incrementAndGet();
            return behaviour.apply(request);
        }
    }

    private static ImpsFtResult success(ImpsFtRequest r) {
        return new ImpsFtResult(r.reqId(), "S", "003712585052", r.custBankAccNo(), "Bene AC Holder", "", "");
    }

    private static ImpsFtResult businessDecline(ImpsFtRequest r) {
        return new ImpsFtResult(r.reqId(), "F", "", r.custBankAccNo(), "", "E01", "invalid account");
    }

    @Test
    void sameIdempotentId_secondCallReturnsPrior_singleTransfer() {
        FakeImpsFt vendor = new FakeImpsFt();
        vendor.behaviour = ImpsDisbursalServiceTest::success;
        var service = new ImpsDisbursalService(vendor, new InMemoryImpsIdempotencyStore());

        ImpsFtResult first = service.transfer(request("IDEM-1", "2026040915306622"), CTX);
        ImpsFtResult second = service.transfer(request("IDEM-1", "2026040915306622"), CTX);

        assertThat(vendor.calls.get()).as("the backend is called ONCE — no double transfer").isEqualTo(1);
        assertThat(first.transactionId()).isEqualTo("003712585052");
        assertThat(second.transactionId()).as("the repeat returns the PRIOR result").isEqualTo("003712585052");
    }

    @Test
    void businessDecline_isDefinitive_andCached() {
        FakeImpsFt vendor = new FakeImpsFt();
        vendor.behaviour = ImpsDisbursalServiceTest::businessDecline;
        var service = new ImpsDisbursalService(vendor, new InMemoryImpsIdempotencyStore());

        ImpsFtResult first = service.transfer(request("IDEM-2", "BAD-ACCOUNT"), CTX);
        ImpsFtResult second = service.transfer(request("IDEM-2", "BAD-ACCOUNT"), CTX);

        assertThat(first.isSuccess()).isFalse();
        assertThat(first.errCode()).isEqualTo("E01");
        assertThat(vendor.calls.get()).as("a business decline is definitive — cached, not re-attempted").isEqualTo(1);
        assertThat(second.errCode()).isEqualTo("E01");
    }

    @Test
    void technicalFailure_isNotCached_soAnAmbiguousTransferStaysRetryable() {
        FakeImpsFt vendor = new FakeImpsFt();
        vendor.behaviour = r -> { throw new SyncTechnicalException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "timeout"); };
        var service = new ImpsDisbursalService(vendor, new InMemoryImpsIdempotencyStore());

        assertThatThrownBy(() -> service.transfer(request("IDEM-3", "2026040915306622"), CTX))
                .isInstanceOf(SyncTechnicalException.class);

        // The retry succeeds — the technical failure did NOT cache a result.
        vendor.behaviour = ImpsDisbursalServiceTest::success;
        ImpsFtResult retry = service.transfer(request("IDEM-3", "2026040915306622"), CTX);
        assertThat(retry.isSuccess()).isTrue();
        assertThat(vendor.calls.get()).as("timeout did not poison the key — retry re-attempts").isEqualTo(2);
    }

    @Test
    void missingIdempotentId_refusesToMoveMoney() {
        FakeImpsFt vendor = new FakeImpsFt();
        vendor.behaviour = ImpsDisbursalServiceTest::success;
        var service = new ImpsDisbursalService(vendor, new InMemoryImpsIdempotencyStore());

        assertThatThrownBy(() -> service.transfer(request("  ", "2026040915306622"), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.code()).isEqualTo("MISSING_IDEMPOTENCY_KEY"));
        assertThat(vendor.calls.get()).as("no idempotency key -> no transfer attempted").isZero();
    }

    @Test
    void unknownOperation_failsClosed() {
        FakeImpsFt vendor = new FakeImpsFt();
        vendor.behaviour = ImpsDisbursalServiceTest::success;
        var service = new ImpsDisbursalService(vendor, new InMemoryImpsIdempotencyStore());

        assertThatThrownBy(() -> service.invoke("balance", java.util.Map.of("idempotentId", "X"), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT));
    }
}

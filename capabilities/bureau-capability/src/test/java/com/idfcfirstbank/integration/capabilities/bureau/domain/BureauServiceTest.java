package com.idfcfirstbank.integration.capabilities.bureau.domain;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Applicant;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauQuery;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauResult;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauScore;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.FetchStatus;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Purpose;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CibilBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CommercialBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.MultiBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.ScorecardInfraPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.service.BureauService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Core fan-out/merge/status behaviour with fake ports (no Spring, no Docker). */
class BureauServiceTest {

    private BureauResult ok(BureauType type, String source) {
        return new BureauResult(type, new BureauScore(720, "M", 300, 900), null, "ref", Instant.now(), source);
    }

    private BureauService service(CibilBureauPort cibil, MultiBureauPort multi,
                                  CommercialBureauPort commercial, ScorecardInfraPort scorecard) {
        return new BureauService(cibil, multi, commercial, scorecard, Runnable::run); // direct executor
    }

    private BureauFetchRequest request(BureauType... types) {
        Applicant a = new Applicant("A", null, "B", LocalDate.of(1990, 1, 1), "ABCPR1234F",
                null, List.of(), "9", "a@b.c", null, null);
        return new BureauFetchRequest(a, List.of(types), Purpose.ELIGIBILITY, "consent", "corr-1");
    }

    @Test
    void allRequestedBureausSucceed_isSuccess() {
        BureauService svc = service(
                q -> ok(BureauType.CIBIL, "cibil"),
                q -> ok(BureauType.MULTI_BUREAU, "multi"),
                q -> { throw new AssertionError("not requested"); },
                q -> { throw new AssertionError("not requested"); });

        BureauFetchResponse r = svc.fetch(request(BureauType.CIBIL, BureauType.MULTI_BUREAU));

        assertThat(r.status()).isEqualTo(FetchStatus.SUCCESS);
        assertThat(r.bureauResults()).extracting(BureauResult::type)
                .containsExactlyInAnyOrder(BureauType.CIBIL, BureauType.MULTI_BUREAU);
        assertThat(r.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void oneBureauFails_isPartial_andOthersStillReturned() {
        BureauService svc = service(
                q -> ok(BureauType.CIBIL, "cibil"),
                q -> { throw new RuntimeException("multi-bureau down"); },
                q -> { throw new AssertionError(); },
                q -> { throw new AssertionError(); });

        BureauFetchResponse r = svc.fetch(request(BureauType.CIBIL, BureauType.MULTI_BUREAU));

        assertThat(r.status()).isEqualTo(FetchStatus.PARTIAL);
        assertThat(r.bureauResults()).extracting(BureauResult::type).containsExactly(BureauType.CIBIL);
    }

    @Test
    void everyBureauFails_isFailed() {
        BureauService svc = service(
                q -> { throw new RuntimeException("x"); },
                q -> { throw new RuntimeException("x"); },
                q -> { throw new RuntimeException("x"); },
                q -> { throw new RuntimeException("x"); });

        BureauFetchResponse r = svc.fetch(request(BureauType.CIBIL, BureauType.COMMERCIAL));

        assertThat(r.status()).isEqualTo(FetchStatus.FAILED);
        assertThat(r.bureauResults()).isEmpty();
    }

    @Test
    void emptyBureauTypes_isFailed_withNoCalls() {
        BureauService svc = service(
                q -> { throw new AssertionError(); }, q -> { throw new AssertionError(); },
                q -> { throw new AssertionError(); }, q -> { throw new AssertionError(); });

        BureauFetchResponse r = svc.fetch(request());

        assertThat(r.status()).isEqualTo(FetchStatus.FAILED);
    }

    @Test
    void dispatchesToTheCorrectPortPerType() {
        BureauService svc = service(
                q -> ok(BureauType.CIBIL, "cibil"),
                q -> { throw new AssertionError(); },
                q -> ok(BureauType.COMMERCIAL, "commercial"),
                q -> ok(BureauType.BUREAU_SCORE, "scorecard"));

        BureauFetchResponse r = svc.fetch(request(BureauType.CIBIL, BureauType.COMMERCIAL, BureauType.BUREAU_SCORE));

        assertThat(r.status()).isEqualTo(FetchStatus.SUCCESS);
        assertThat(r.bureauResults()).extracting(BureauResult::source)
                .containsExactlyInAnyOrder("cibil", "commercial", "scorecard");
    }
}

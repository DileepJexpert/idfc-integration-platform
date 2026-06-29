package com.idfcfirstbank.integration.capabilities.bureau.domain;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Applicant;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauQuery;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Purpose;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Canonical-model sanity checks (framework-free domain; no Spring/Docker). */
class BureauModelTest {

    private Applicant applicant() {
        return new Applicant("Asha", null, "Rao", LocalDate.of(1990, 1, 1), "ABCPR1234F",
                null, List.of(), "9999999999", "asha@example.com", null, null);
    }

    @Test
    void request_defensivelyCopiesBureauTypes_andQueryDerivesFromIt() {
        var types = new java.util.ArrayList<>(List.of(BureauType.CIBIL, BureauType.MULTI_BUREAU));
        var request = new BureauFetchRequest(applicant(), types, Purpose.ELIGIBILITY, "consent-1", "corr-1");

        types.clear(); // mutate the source list
        assertThat(request.bureauTypes()).containsExactly(BureauType.CIBIL, BureauType.MULTI_BUREAU);

        BureauQuery query = BureauQuery.from(request);
        assertThat(query.purpose()).isEqualTo(Purpose.ELIGIBILITY);
        assertThat(query.consentRef()).isEqualTo("consent-1");
        assertThat(query.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void bureauTypeVocabulary_matchesCanonicalApi() {
        assertThat(BureauType.values())
                .containsExactlyInAnyOrder(BureauType.CIBIL, BureauType.MULTI_BUREAU,
                        BureauType.COMMERCIAL, BureauType.BUREAU_SCORE);
    }

    @Test
    void returnedCollections_areImmutable() {
        var request = new BureauFetchRequest(applicant(), List.of(BureauType.CIBIL),
                Purpose.UNDERWRITING, "consent-2", "corr-2");
        assertThatThrownBy(() -> request.bureauTypes().add(BureauType.COMMERCIAL))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}

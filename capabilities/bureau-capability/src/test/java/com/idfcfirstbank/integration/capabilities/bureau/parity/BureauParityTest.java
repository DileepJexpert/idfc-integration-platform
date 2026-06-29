package com.idfcfirstbank.integration.capabilities.bureau.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.mock.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.mock.MockCommercialBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.mock.MockMultiBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.mock.MockScorecardInfraAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Applicant;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.Purpose;
import com.idfcfirstbank.integration.capabilities.bureau.domain.service.BureauService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity harness for the Bureau capability — the correctness gate (build-doc
 * step 8). It runs recorded fixtures (applicant → expected canonical bureau data)
 * through the capability and asserts equivalence within the allowlist
 * (fetchedAt, rawRef, source). NOT a live old-service — fixtures are captured
 * pairs.
 *
 * <p>NOTE: these fixtures are SYNTHETIC placeholders that mirror the deterministic
 * mock adapters; the REAL fixtures are captured from the absorbed services'
 * output during the harvest/cutover (steps 3/9). The oracle + allowlist mechanism
 * is real and is what the captured fixtures will run against.
 */
class BureauParityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BureauParityOracle oracle = new BureauParityOracle();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    private BureauService capabilityWithMocks() {
        return new BureauService(
                new MockCibilAdapter(clock), new MockMultiBureauAdapter(clock),
                new MockCommercialBureauAdapter(clock), new MockScorecardInfraAdapter(clock),
                Runnable::run);
    }

    @Test
    void recordedFixture_matchesCapabilityOutput_withinAllowlist() throws Exception {
        Fixture fx = load("parity/cibil-multi-prime.json");
        BureauFetchResponse response = capabilityWithMocks().fetch(fx.toRequest());

        List<String> diffs = oracle.diff(fx.expectedSnapshot(), BureauParitySnapshot.of(response));
        assertThat(diffs).as("parity diffs").isEmpty();
    }

    @Test
    void outOfAllowlistDifference_failsParity_andReportsDiff() throws Exception {
        Fixture fx = load("parity/cibil-score-mismatch-negative.json");
        BureauFetchResponse response = capabilityWithMocks().fetch(fx.toRequest());

        List<String> diffs = oracle.diff(fx.expectedSnapshot(), BureauParitySnapshot.of(response));
        assertThat(diffs).anyMatch(d -> d.startsWith("CIBIL"));
        assertThat(oracle.isParity(fx.expectedSnapshot(), BureauParitySnapshot.of(response))).isFalse();
    }

    // --- fixture parsing ---------------------------------------------------------

    private Fixture load(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s on classpath", resource).isNotNull();
            return new Fixture(mapper.readTree(in));
        }
    }

    /** Thin wrapper over the fixture JSON: builds the domain request + expected snapshot. */
    private record Fixture(JsonNode root) {

        BureauFetchRequest toRequest() {
            JsonNode req = root.get("request");
            JsonNode ap = req.get("applicant");
            Applicant applicant = new Applicant(
                    text(ap, "firstName"), text(ap, "middleName"), text(ap, "lastName"),
                    ap.hasNonNull("dob") ? LocalDate.parse(ap.get("dob").asText()) : null,
                    text(ap, "pan"), text(ap, "aadharRef"), List.of(),
                    text(ap, "phone"), text(ap, "email"), null, null);
            List<BureauType> types = new ArrayList<>();
            req.get("bureauTypes").forEach(n -> types.add(BureauType.valueOf(n.asText())));
            return new BureauFetchRequest(applicant, types,
                    Purpose.valueOf(req.get("purpose").asText()), text(req, "consentRef"), "parity-corr");
        }

        BureauParitySnapshot expectedSnapshot() {
            JsonNode exp = root.get("expected");
            var byType = new TreeMap<String, BureauParitySnapshot.ResultProjection>();
            exp.get("byType").fields().forEachRemaining(e -> {
                JsonNode p = e.getValue();
                byType.put(e.getKey(), new BureauParitySnapshot.ResultProjection(
                        p.get("scoreValue").asInt(), p.get("scoreModel").asText(),
                        p.get("segment").asText(), p.get("totalTradelines").asInt(),
                        p.get("totalOutstanding").asText(), p.get("overdueAmount").asText()));
            });
            return new BureauParitySnapshot(exp.get("status").asText(), byType);
        }

        private static String text(JsonNode n, String field) {
            return n.hasNonNull(field) ? n.get(field).asText() : null;
        }
    }
}

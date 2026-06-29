package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the pure engine through the REAL locked journey (customer -> kyc ->
 * bureau -> scoring -> decide -> book/reject), proving the async DAG walk:
 * one capability request per task, the branch routing on the scoring decision,
 * and the terminal decision (with loanId on the approved path).
 */
class JourneyEngineTest {

    private final JourneyEngine engine = new JourneyEngine(new ExpressionEvaluator());
    private final JourneyDefinition def =
            new JourneyDefinitionLoader(new ObjectMapper()).loadFromClasspath("journeys/loan-origination.journey.json");

    private JourneyInstance newInstance() {
        return new JourneyInstance("ji-1", "corr-1", def.key(), "APP-1", Map.of("pan", "ABCDE1234F"));
    }

    /** Feed an OK capability response for {@code node}/{@code cap} and return the engine's next step. */
    private EngineOutcome advance(JourneyInstance instance, String node, String cap, Map<String, Object> result) {
        return engine.onCapabilityResponse(def, instance,
                new CapabilityResponse("ji-1", "corr-1", node, cap, CapabilityStatus.OK, result));
    }

    @Test
    void approvedPathWalksEveryNodeAndBooks() {
        JourneyInstance instance = newInstance();

        EngineOutcome start = engine.start(def, instance);
        assertThat(start.requests()).singleElement().satisfies(r -> {
            assertThat(r.capabilityKey()).isEqualTo("customer-party");
            assertThat(r.nodeId()).isEqualTo("n_customer");
        });

        assertEmits(advance(instance, "n_customer", "customer-party", Map.of("crn", "CRN-1")), "kyc", "n_kyc");
        assertEmits(advance(instance, "n_kyc", "kyc", Map.of("kycStatus", "VERIFIED")), "bureau", "n_bureau");
        assertEmits(advance(instance, "n_bureau", "bureau", Map.of("bureauScore", 780)), "scoring", "n_score");

        // scoring returns APPROVED -> branch routes to the booking node
        EngineOutcome afterScore = advance(instance, "n_score", "scoring", Map.of("decision", "APPROVED", "score", 780));
        assertEmits(afterScore, "lending-origination", "n_book");
        // the booking request can see the upstream bureau result (collectedResults)
        assertThat(afterScore.requests().get(0).collectedResults()).containsKey("bureau");

        EngineOutcome afterBook = advance(instance, "n_book", "lending-origination",
                Map.of("loanId", "LN-900", "status", "BOOKED"));
        assertThat(afterBook.requests()).isEmpty();
        assertThat(afterBook.decision()).hasValueSatisfying(d -> {
            assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
            assertThat(d.loanId()).isEqualTo("LN-900");
            assertThat(d.terminalNodeId()).isEqualTo("n_done");
            assertThat(d.emitted()).containsExactly("LoanBooked");
        });
        assertThat(instance.status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void rejectedPathStopsAtRejectTerminalWithoutBooking() {
        JourneyInstance instance = newInstance();
        engine.start(def, instance);
        advance(instance, "n_customer", "customer-party", Map.of("crn", "CRN-1"));
        advance(instance, "n_kyc", "kyc", Map.of("kycStatus", "VERIFIED"));
        advance(instance, "n_bureau", "bureau", Map.of("bureauScore", 540));

        EngineOutcome afterScore = advance(instance, "n_score", "scoring", Map.of("decision", "REJECTED", "score", 540));
        assertThat(afterScore.requests()).isEmpty(); // reject terminal is reached synchronously
        assertThat(afterScore.decision()).hasValueSatisfying(d -> {
            assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
            assertThat(d.loanId()).isNull();
            assertThat(d.terminalNodeId()).isEqualTo("n_reject");
        });
    }

    @Test
    void errorResponseFailsTheJourney() {
        JourneyInstance instance = newInstance();
        engine.start(def, instance);
        EngineOutcome out = engine.onCapabilityResponse(def, instance,
                new CapabilityResponse("ji-1", "corr-1", "n_customer", "customer-party",
                        CapabilityStatus.ERROR, Map.of()));
        assertThat(out.decision()).hasValueSatisfying(d -> assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR));
        assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
    }

    private void assertEmits(EngineOutcome outcome, String expectCap, String expectNode) {
        assertThat(outcome.requests()).singleElement().satisfies(r -> {
            assertThat(r.capabilityKey()).isEqualTo(expectCap);
            assertThat(r.nodeId()).isEqualTo(expectNode);
        });
    }
}

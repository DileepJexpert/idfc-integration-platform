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

    @Test
    void bookingErrorFailsTheJourney_compensationIsT2() {
        JourneyInstance instance = newInstance();
        engine.start(def, instance);
        advance(instance, "n_customer", "customer-party", Map.of("crn", "CRN-1"));
        advance(instance, "n_kyc", "kyc", Map.of("kycStatus", "VERIFIED"));
        advance(instance, "n_bureau", "bureau", Map.of("bureauScore", 780));
        advance(instance, "n_score", "scoring", Map.of("decision", "APPROVED", "score", 780));

        // The booking capability FAILS. n_book declares onFailure: "compensate", but
        // saga compensation execution is a T2 capability — at T1 the journey fails.
        EngineOutcome afterBookError = engine.onCapabilityResponse(def, instance,
                new CapabilityResponse("ji-1", "corr-1", "n_book", "lending-origination",
                        CapabilityStatus.ERROR, Map.of()));

        assertThat(afterBookError.requests()).isEmpty();
        assertThat(afterBookError.decision()).hasValueSatisfying(d -> {
            assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
            assertThat(d.terminalNodeId()).isEqualTo("n_book");
        });
        assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
    }

    @Test
    void decisionEchoesTheInboundEdgeIdentity() {
        // The engine echoes source/notificationId from the run payload so an edge
        // can route the decision back over Kafka (P1 decision-return path).
        JourneyInstance instance = new JourneyInstance("ji-2", "corr-2", def.key(), "APP-2",
                Map.of("source", "SFDC", "notificationId", "ntf-9", "sfdcRecordId", "rec-9"));
        engine.start(def, instance);
        advance(instance, "n_customer", "customer-party", Map.of("crn", "CRN-1"));
        advance(instance, "n_kyc", "kyc", Map.of("kycStatus", "VERIFIED"));
        advance(instance, "n_bureau", "bureau", Map.of("bureauScore", 540));
        EngineOutcome afterScore =
                engine.onCapabilityResponse(def, instance, new CapabilityResponse("ji-2", "corr-2",
                        "n_score", "scoring", CapabilityStatus.OK, Map.of("decision", "REJECTED")));

        assertThat(afterScore.decision()).hasValueSatisfying(d -> {
            assertThat(d.source()).isEqualTo("SFDC");
            assertThat(d.notificationId()).isEqualTo("ntf-9");
            assertThat(d.sfdcRecordId()).isEqualTo("rec-9");
        });
    }

    @Test
    void unknownTerminalStatusFailsClosedNeverApproves() {
        // Phase 3 defense-in-depth: the loader rejects unknown statuses, but a
        // definition built any other way (registry, tests, future loaders) must
        // ALSO fail closed at runtime — a typo can never approve a loan.
        com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode task =
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode.task(
                        "t", null, "kyc", null, null, "context.kyc", null, null, null, false,
                        java.util.List.of("end"));
        com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode typoTerminal =
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode.terminal(
                        "end", "push_decision_to_channel", java.util.List.of(), "aproved"); // typo
        JourneyDefinition typoDef = new JourneyDefinition("typo-journey", "t", java.util.List.of(task, typoTerminal));

        JourneyInstance instance = new JourneyInstance("ji-typo", "corr-t", "typo-journey", "APP-T", Map.of());
        engine.start(typoDef, instance);
        EngineOutcome outcome = engine.onCapabilityResponse(typoDef, instance,
                new CapabilityResponse("ji-typo", "corr-t", "t", "kyc", CapabilityStatus.OK, Map.of()));

        assertThat(outcome.decision()).hasValueSatisfying(d ->
                assertThat(d.outcome())
                        .as("an unknown terminal status must produce ERROR — NEVER an APPROVED decision")
                        .isEqualTo(JourneyDecision.ERROR));
        assertThat(instance.status()).isEqualTo(InstanceStatus.FAILED);
    }

    @Test
    void unmatchedBranchExceptionCarriesIdsOnlyNeverTheApplicantPayload() {
        // Phase 3 PII: this exception propagates into logs/DLQ — it must not
        // embed the evaluation context (PAN, mobile, ...), only ids.
        JourneyInstance instance = new JourneyInstance("ji-pii", "corr-p", def.key(), "APP-P",
                Map.of("pan", "SECRETPAN99"));
        engine.start(def, instance);
        advance(instance, "n_customer", "customer-party", Map.of("crn", "CRN-1"));
        advance(instance, "n_kyc", "kyc", Map.of("kycStatus", "VERIFIED"));
        advance(instance, "n_bureau", "bureau", Map.of("bureauScore", 700));

        // A branch with no matching arm AND no default cannot happen with the
        // locked journey (it has a default) — drive a defaultless branch directly.
        com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode task =
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode.task(
                        "t", null, "kyc", null, null, null, null, null, null, false, java.util.List.of("b"));
        com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode branch =
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode.branch(
                        "b", null, java.util.List.of(
                                new com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm(
                                        "context.never == 'x'", "end")), null);
        com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode end =
                com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode.terminal(
                        "end", "push", java.util.List.of(), "completed");
        JourneyDefinition noDefault = new JourneyDefinition("nd", "t", java.util.List.of(task, branch, end));
        JourneyInstance piiInstance = new JourneyInstance("ji-nd", "corr-nd", "nd", "APP-ND",
                Map.of("pan", "SECRETPAN99"));
        engine.start(noDefault, piiInstance);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        engine.onCapabilityResponse(noDefault, piiInstance, new CapabilityResponse(
                                "ji-nd", "corr-nd", "t", "kyc", CapabilityStatus.OK, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ji-nd")
                .satisfies(e -> assertThat(e.getMessage())
                        .as("no applicant data in the exception message")
                        .doesNotContain("SECRETPAN99"));
    }

    private void assertEmits(EngineOutcome outcome, String expectCap, String expectNode) {
        assertThat(outcome.requests()).singleElement().satisfies(r -> {
            assertThat(r.capabilityKey()).isEqualTo(expectCap);
            assertThat(r.nodeId()).isEqualTo(expectNode);
        });
    }
}

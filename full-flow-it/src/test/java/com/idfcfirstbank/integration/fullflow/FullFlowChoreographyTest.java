package com.idfcfirstbank.integration.fullflow;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.ClasspathJourneySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauFetchService;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.MockPosidexAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.application.CustomerPartyService;
import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.MockNsdlAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.application.KycService;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.MockFinnOneAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.application.LendingOriginationService;
import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.MockFicoAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.application.ScoringService;
import com.idfcfirstbank.integration.capabilities.scoring.domain.service.DecisionRule;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyRegistry;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FULL-FLOW demo proof, Docker-free. Wires the REAL engine to the REAL capability
 * services (customer-party, kyc, bureau, scoring, lending-origination) over an
 * in-memory bus that stands in for Kafka: a capability request is handed to the
 * matching service and its response is fed straight back to the engine. This
 * exercises the real DecisionRule, the real bureau score, and the real booking —
 * the same code the Kafka adapters drive in the live compose stack.
 *
 * <p>It proves the branch both ways: a high-score applicant is APPROVED and booked
 * (loanId), a "LOW" applicant is REJECTED with no booking. The compose-based live
 * run (with real Kafka + the mock vendor servers) is the demo script.
 */
class FullFlowChoreographyTest {

    /** In-memory bus: route each capability request to its real service, feed the reply back. */
    private static final class InMemoryBus implements CapabilityRequestPort {
        private final Map<String, Function<CapabilityRequest, CapabilityResponse>> fleet;
        private JourneyOrchestrator orchestrator;

        InMemoryBus(Map<String, Function<CapabilityRequest, CapabilityResponse>> fleet) {
            this.fleet = fleet;
        }

        void bind(JourneyOrchestrator orchestrator) {
            this.orchestrator = orchestrator;
        }

        @Override
        public void publish(CapabilityRequest request) {
            Function<CapabilityRequest, CapabilityResponse> capability = fleet.get(request.capabilityKey());
            if (capability == null) {
                throw new IllegalStateException("no capability wired for '" + request.capabilityKey() + "'");
            }
            orchestrator.onCapabilityResponse(capability.apply(request));
        }
    }

    private JourneyDecision runFlow(String pan) {
        // Real capability fleet (each = the production service + its mock vendor adapter).
        Map<String, Function<CapabilityRequest, CapabilityResponse>> fleet = Map.of(
                "customer-party", new CustomerPartyService(new MockPosidexAdapter())::handle,
                "kyc", new KycService(new MockNsdlAdapter())::handle,
                "bureau", new BureauService(
                        new BureauFetchService(java.util.List.of(new MockCibilAdapter())),
                        java.util.List.of(BureauType.CIBIL))::handle,
                "scoring", new ScoringService(new MockFicoAdapter(), new DecisionRule(), 700)::handle,
                "lending-origination", new LendingOriginationService(new MockFinnOneAdapter())::handle);

        InMemoryBus bus = new InMemoryBus(fleet);
        AtomicReference<JourneyDecision> decision = new AtomicReference<>();
        DecisionOutboundPort decisionPort = decision::set;

        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper())
                .loadFromClasspath("journeys/loan-origination.journey.json");
        JourneyRegistry registry = new JourneyRegistry(
                new ClasspathJourneySource(new JourneyDefinitionLoader(new ObjectMapper()),
                        List.of("journeys/loan-origination.journey.json")),
                Map.of("PERSONAL_LOAN", "loan-origination"));
        registry.bootstrap();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                registry,
                new InMemoryJourneyInstanceStore(),
                bus, decisionPort, () -> "ji-fullflow");
        bus.bind(orchestrator);

        orchestrator.onOrigination(Map.of(
                "type", "PERSONAL_LOAN", "correlationId", "corr-ff", "applicationRef", "APP-1",
                "payload", Map.of("pan", pan, "name", "Asha")));

        JourneyDecision d = decision.get();
        assertThat(d).as("journey should have reached a decision").isNotNull();
        return d;
    }

    @Test
    void highScoreApplicantFlowsAllTheWayToAnApprovedBookedLoan() {
        JourneyDecision d = runFlow("ABCDE1234F");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.loanId()).isNotBlank();
        assertThat(d.terminalNodeId()).isEqualTo("n_done");
    }

    @Test
    void lowScoreApplicantIsRejectedWithoutBooking() {
        JourneyDecision d = runFlow("LOWAB0000X");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.loanId()).isNull();
        assertThat(d.terminalNodeId()).isEqualTo("n_reject");
    }
}

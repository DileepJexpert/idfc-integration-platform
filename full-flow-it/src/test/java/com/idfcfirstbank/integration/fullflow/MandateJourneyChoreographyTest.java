package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock.MockAutopayLinkAdapter;
import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock.MockCbsNachAdapter;
import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock.MockEnachVerificationAdapter;
import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock.MockVendorMandateAdapter;
import com.idfcfirstbank.integration.capabilities.mandate.adapter.out.store.InMemoryMandateStore;
import com.idfcfirstbank.integration.capabilities.mandate.application.MandateCapability;
import com.idfcfirstbank.integration.capabilities.mandate.application.MandateService;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateEventPort;
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
import com.idfcfirstbank.integration.shared.capability.CapabilityDispatcher;
import com.idfcfirstbank.integration.shared.capability.InMemoryCapabilityIdempotencyStore;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BRD §8 proof, Docker-free: the two e-mandate journeys ({@code emandate-autopay-setup}
 * and {@code emandate-cancel}) run end-to-end over the REAL engine and the REAL
 * {@link MandateService}, dispatched through the SHARED capability framework
 * ({@link CapabilityDispatcher} + idempotency store) — the same shell the Kafka
 * auto-configuration wires in production.
 *
 * <p>It proves config-not-code: nothing here is journey-specific. The mandate
 * capability is wired ONCE; which operations run, in what order, and how the
 * cancel branch decides are all read from the journey JSON. The cancel branch is
 * proven both ways — a known mandate is cancelled (n_done), a MISSING one is
 * reported not-found (n_notFound) — purely from {@code context.cancel.found}.
 */
class MandateJourneyChoreographyTest {

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

    /** A fresh mandate fleet: the real service behind the shared dispatcher. */
    private static Map<String, Function<CapabilityRequest, CapabilityResponse>> mandateFleet() {
        MandateEventPort events = (invoiceNo, event) -> { /* no-op: wait/correlation is T3 */ };
        MandateService service = new MandateService(
                new MockVendorMandateAdapter(),
                new MockEnachVerificationAdapter(),
                new MockAutopayLinkAdapter(),
                new MockCbsNachAdapter(),
                new InMemoryMandateStore(),
                events);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(
                new MandateCapability(service), new InMemoryCapabilityIdempotencyStore());
        return Map.of("mandate", dispatcher::handle);
    }

    private JourneyDecision runJourney(String contractFile, String correlationId, String invoiceNo) {
        InMemoryBus bus = new InMemoryBus(mandateFleet());
        AtomicReference<JourneyDecision> decision = new AtomicReference<>();
        DecisionOutboundPort decisionPort = decision::set;

        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper())
                .loadFromClasspath("journeys/" + contractFile);
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                new JourneyRegistry(List.of(def), Map.of()),
                new InMemoryJourneyInstanceStore(),
                bus, decisionPort, () -> "ji-" + correlationId);
        bus.bind(orchestrator);

        orchestrator.onOrigination(Map.of(
                "type", def.key(), "correlationId", correlationId, "applicationRef", "APP-1",
                "payload", Map.of("invoiceNo", invoiceNo, "vendor", "DIGIO")));

        JourneyDecision d = decision.get();
        assertThat(d).as("journey should have reached a decision").isNotNull();
        return d;
    }

    @Test
    void autopaySetupRunsToCompletedAndEmitsTheLink() {
        JourneyDecision d = runJourney("emandate-autopay-setup.journey.json", "corr-autopay", "INV-1001");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_done");
        assertThat(d.emitted()).contains("AutopayLinkSent");
    }

    @Test
    void cancelOfAKnownMandateTakesTheFoundArm() {
        JourneyDecision d = runJourney("emandate-cancel.journey.json", "corr-cancel", "INV-2002");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_done");
        assertThat(d.emitted()).contains("MandateCancelled");
    }

    @Test
    void cancelOfAMissingMandateTakesTheDefaultArm() {
        JourneyDecision d = runJourney("emandate-cancel.journey.json", "corr-missing", "INV-MISSING-3003");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.terminalNodeId()).isEqualTo("n_notFound");
        assertThat(d.emitted()).contains("MandateNotFound");
    }
}

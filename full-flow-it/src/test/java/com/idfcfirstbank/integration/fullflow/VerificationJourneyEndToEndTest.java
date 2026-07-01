package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.AdapterRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationService;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcResponseMapper;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaVehicleRcAdapter;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.route.ConfigRouteResolver;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Retry;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Route;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
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
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STEP-2 TEMPLATE PROOF (Docker-free): the real golden Karza VAHAN-RC verification runs
 * end-to-end — SFDC-style origination -> engine -> verification capability (control-plane
 * route + OAuth adapter + real mappers) -> Karza stub -> {@code task -> branch} -> decision.
 * Proves BOTH branches (ACTIVE/CLEAR -> proceed; blacklisted -> decline) AND the DLQ path
 * (downstream 500 -> retry -> DLQ, never lost). This is the pattern the other 3 svcNames copy.
 */
class VerificationJourneyEndToEndTest {

    /** In-memory bus: route each capability request to its service, feed the reply back. */
    private static final class InMemoryBus implements CapabilityRequestPort {
        private final Map<String, Function<CapabilityRequest, CapabilityResponse>> fleet;
        private JourneyOrchestrator orchestrator;
        InMemoryBus(Map<String, Function<CapabilityRequest, CapabilityResponse>> fleet) { this.fleet = fleet; }
        void bind(JourneyOrchestrator o) { this.orchestrator = o; }
        @Override public void publish(CapabilityRequest request) {
            orchestrator.onCapabilityResponse(fleet.get(request.capabilityKey()).apply(request));
        }
    }

    private HttpServer karza;
    private volatile boolean fail500 = false;
    private final List<String> dlq = new ArrayList<>();

    @BeforeEach
    void startKarza() throws IOException {
        karza = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        karza.createContext("/karza/vahan-rc", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (fail500) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            boolean blacklisted = reqBody.contains("XX00YY0000");   // fail stub by reg_no (mirrors WireMock)
            String body = blacklisted
                    ? "{\"metadata\":{\"status\":\"ACTIVE\"},\"resource_data\":[{\"rcStatus\":\"ACTIVE\",\"blackListStatus\":\"BLACKLISTED\",\"registrationNumber\":\"XX00YY0000\"}]}"
                    : "{\"metadata\":{\"status\":\"ACTIVE\"},\"resource_data\":[{\"rcStatus\":\"ACTIVE\",\"blackListStatus\":\"NO\",\"registrationNumber\":\"AB12CD1234\",\"ownerName\":\"ASHA KUMAR\"}]}";
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        karza.start();
    }

    @AfterEach
    void stopKarza() { karza.stop(0); }

    private JourneyDecision run(String registrationNumber) {
        String baseUrl = "http://127.0.0.1:" + karza.getAddress().getPort() + "/karza/vahan-rc";
        VerificationProperties props = new VerificationProperties(
                List.of(new Route("KARZA_VAHAN_RC", baseUrl, "OAUTH_BEARER")),
                List.of("127.0.0.1"),
                new Retry(2, 0),
                "cap.verification.dlq.v1");

        MapperRegistry mappers = new MapperRegistry();
        mappers.register("KARZA_VAHAN_RC", new MapperPair(new KarzaVahanRcRequestMapper(), new KarzaVahanRcResponseMapper()));
        VerificationService service = new VerificationService(
                new ConfigRouteResolver(props),
                new AdapterRegistry(List.of(new KarzaVehicleRcAdapter(svc -> "tok-" + svc))),
                mappers);
        VerificationDlqPort dlqPort = (req, reason) -> dlq.add(req.operation() + ":" + reason);
        VerificationDispatcher dispatcher = new VerificationDispatcher(service, dlqPort, 2, 0);

        InMemoryBus bus = new InMemoryBus(Map.of("verification", dispatcher::handle));
        AtomicReference<JourneyDecision> decision = new AtomicReference<>();
        DecisionOutboundPort decisionPort = decision::set;

        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper())
                .loadFromClasspath("journeys/vehicle-rc-verification.journey.json");
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                new JourneyRegistry(List.of(def), Map.of()),
                new InMemoryJourneyInstanceStore(),
                bus, decisionPort, () -> "ji-verify");
        bus.bind(orchestrator);

        orchestrator.onOrigination(Map.of(
                "type", "VEHICLE_RC", "correlationId", "corr-" + registrationNumber, "applicationRef", "APP-1",
                "payload", Map.of("registrationNumber", registrationNumber, "consent", "Y")));

        JourneyDecision d = decision.get();
        assertThat(d).as("journey reached a decision").isNotNull();
        return d;
    }

    @Test
    void activeAndClearVehicleProceeds() {
        JourneyDecision d = run("AB12CD1234");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_proceed");
        assertThat(d.emitted()).contains("VehicleRcApproved");
        assertThat(dlq).isEmpty();
    }

    @Test
    void blacklistedVehicleDeclines() {
        JourneyDecision d = run("XX00YY0000");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.terminalNodeId()).isEqualTo("n_decline");
        assertThat(d.emitted()).contains("VehicleRcDeclined");
        assertThat(dlq).isEmpty();
    }

    @Test
    void downstream500RetriesThenDeadLettersAndFailsTheNode() {
        fail500 = true;
        JourneyDecision d = run("AB12CD1234");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(d.terminalNodeId()).isEqualTo("n_rcError");
        assertThat(dlq).as("verification was dead-lettered, not lost").hasSize(1);
        assertThat(dlq.get(0)).contains("KARZA_VAHAN_RC");
    }
}

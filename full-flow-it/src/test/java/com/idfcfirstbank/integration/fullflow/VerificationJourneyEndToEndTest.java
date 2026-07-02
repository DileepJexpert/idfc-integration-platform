package com.idfcfirstbank.integration.fullflow;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.ClasspathJourneySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.AdapterRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationService;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaVahanRcResponseMapper;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaClient;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaVehicleRcAdapter;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.route.ConfigRouteResolver;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Retry;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties.Route;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.SfdcNotifyPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.capability.Backoff;
import com.idfcfirstbank.integration.shared.capability.RetryExecutor;
import com.idfcfirstbank.integration.shared.capability.RetryPolicy;
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
 * STEP-2 TEMPLATE PROOF (Docker-free, spec v2): golden Karza VAHAN-RC runs end-to-end
 * through the engine + verification capability (control-plane route + OAuth adapter + D.1
 * mappers + classified retry engine) + Karza stub + {@code task->branch}. Proves the full
 * v2 matrix: proceed, business-decline (->branch, NOT DLQ), TRANSIENT retry -> DLQ+notify,
 * PERMANENT -> DLQ+notify. The pattern the other svcNames copy.
 */
class VerificationJourneyEndToEndTest {

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
    private volatile int httpStatus = 200;    // 200 = normal; 503 = TRANSIENT; 400 = PERMANENT
    private final List<String> dlq = new ArrayList<>();
    private final List<String> notified = new ArrayList<>();

    @BeforeEach
    void startKarza() throws IOException {
        karza = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        karza.createContext("/karza/vahan-rc", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (httpStatus != 200) {
                exchange.sendResponseHeaders(httpStatus, -1);
                exchange.close();
                return;
            }
            boolean blacklisted = reqBody.contains("XX00YY0000");
            String inner = blacklisted
                    ? "{\"registrationNumber\":\"XX00YY0000\",\"rcStatus\":\"ACTIVE\",\"blackListStatus\":\"BLACKLIST\"}"
                    : "{\"registrationNumber\":\"AB12CD1234\",\"ownerName\":\"JOHN DOE\",\"rcStatus\":\"ACTIVE\",\"blackListStatus\":\"CLEAR\"}";
            String body = "{\"metadata\":{\"status\":\"SUCCESS\"},\"resource_data\":[{\"requestId\":\"req-1\",\"statusCode\":200,\"result\":" + inner + "}]}";
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
                List.of("127.0.0.1"), new Retry(3, 0, 0, false),
                "cap.verification.dlq.v1", "sfdc.response.notify.v1");

        MapperRegistry mappers = new MapperRegistry();
        mappers.register("KARZA_VAHAN_RC", new MapperPair(new KarzaVahanRcRequestMapper(), new KarzaVahanRcResponseMapper()));
        VerificationService service = new VerificationService(
                new ConfigRouteResolver(props),
                new AdapterRegistry(List.of(new KarzaVehicleRcAdapter(new KarzaClient(svc -> "tok-" + svc)))), mappers);
        VerificationDlqPort dlqPort = (req, reason) -> dlq.add(req.operation() + ":" + reason);
        SfdcNotifyPort notifyPort = (req, reason) -> notified.add(req.operation() + ":" + reason);
        VerificationDispatcher dispatcher = new VerificationDispatcher(
                service, dlqPort, notifyPort,
                new RetryExecutor(millis -> { }), RetryPolicy.idempotentReads(3, Backoff.fixed(0)));

        InMemoryBus bus = new InMemoryBus(Map.of("verification", dispatcher::handle));
        AtomicReference<JourneyDecision> decision = new AtomicReference<>();
        DecisionOutboundPort decisionPort = decision::set;

        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper())
                .loadFromClasspath("journeys/vehicle-rc-verification.journey.json");
        JourneyRegistry registry = new JourneyRegistry(
                new ClasspathJourneySource(new JourneyDefinitionLoader(new ObjectMapper()),
                        List.of("journeys/vehicle-rc-verification.journey.json")),
                Map.of("VEHICLE_RC", def.key()));
        registry.bootstrap();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                registry,
                new InMemoryJourneyInstanceStore(), bus, decisionPort, () -> "ji-verify");
        bus.bind(orchestrator);

        orchestrator.onOrigination(Map.of(
                "type", "VEHICLE_RC", "correlationId", "corr-" + registrationNumber, "applicationRef", "APP-1",
                "payload", Map.of("registrationNumber", registrationNumber, "consent", "Y")));

        JourneyDecision d = decision.get();
        assertThat(d).as("journey reached a decision").isNotNull();
        return d;
    }

    @Test
    void activeAndClearProceeds() {
        JourneyDecision d = run("AB12CD1234");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_proceed");
        assertThat(dlq).isEmpty();
        assertThat(notified).as("a proceed does not notify a failure").isEmpty();
    }

    @Test
    void blacklistedDeclines_businessNotTechnical_noDlqNoNotify() {
        JourneyDecision d = run("XX00YY0000");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.terminalNodeId()).isEqualTo("n_decline");
        assertThat(dlq).as("a business decline is NOT a technical failure").isEmpty();
        assertThat(notified).isEmpty();
    }

    @Test
    void transient503RetriesThenDlqPlusNotifyAndFailsTheNode() {
        httpStatus = 503;
        JourneyDecision d = run("AB12CD1234");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(d.terminalNodeId()).isEqualTo("n_rcError");
        assertThat(dlq).hasSize(1);
        assertThat(notified).as("SFDC notified of the FAILED run").hasSize(1);
    }

    @Test
    void permanent400GoesStraightToDlqPlusNotify() {
        httpStatus = 400;
        JourneyDecision d = run("AB12CD1234");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(dlq).hasSize(1);
        assertThat(dlq.get(0)).contains("PERMANENT");
        assertThat(notified).hasSize(1);
    }
}

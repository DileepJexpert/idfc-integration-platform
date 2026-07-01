package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.AdapterRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperPair;
import com.idfcfirstbank.integration.capabilities.verification.application.MapperRegistry;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationService;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaDomainCheckRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaNegativeAreaRequestMapper;
import com.idfcfirstbank.integration.capabilities.verification.application.mapper.KarzaResourceDataResponseMapper;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaClient;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaDomainCheckAdapter;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.karza.KarzaNegativeAreaAdapter;
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
 * STEP 3 (spec v2 D.2/D.3): KARZA_DOMAIN_CHECK and ENT_KARZA_NEGATIVE_AREA_TAGGING, each a
 * copy of the step-2 template, proven on the SAME v2 matrix: proceed; business-decline ->
 * branch (NOT DLQ/notify); TRANSIENT 503 -> retry -> DLQ + notify; PERMANENT 400 -> DLQ + notify.
 */
class Step3VerificationEndToEndTest {

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
    private volatile int httpStatus = 200;
    private final List<String> dlq = new ArrayList<>();
    private final List<String> notified = new ArrayList<>();

    @BeforeEach
    void startKarza() throws IOException {
        karza = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        karza.createContext("/karza/domain-check", exchange -> respond(exchange, req ->
                req.contains("temp@disposable.com")
                        ? "{\"result\":true,\"data\":{\"disposable\":true},\"additional_info\":{\"company_info\":{\"org_domain_match\":[{\"match\":false}]}}}"
                        : "{\"result\":true,\"data\":{\"disposable\":false},\"additional_info\":{\"company_info\":{\"org_domain_match\":[{\"match\":true}]}}}"));
        karza.createContext("/karza/negative-area", exchange -> respond(exchange, req ->
                req.contains("RISK")
                        ? "{\"is_negative\":true,\"score\":0.85}"
                        : "{\"is_negative\":false,\"score\":0.10}"));
        karza.start();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, Function<String, String> innerFor) throws IOException {
        String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (httpStatus != 200) {
            exchange.sendResponseHeaders(httpStatus, -1);
            exchange.close();
            return;
        }
        String inner = innerFor.apply(reqBody);
        String body = "{\"metadata\":{\"status\":\"SUCCESS\"},\"resource_data\":[{\"requestId\":\"r1\",\"statusCode\":200,\"result\":" + inner + "}]}";
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @AfterEach
    void stopKarza() { karza.stop(0); }

    private JourneyDecision run(String journeyFile, String svcName, String path, MapperPair mapperPair,
                               Map<String, Object> payload) {
        String baseUrl = "http://127.0.0.1:" + karza.getAddress().getPort() + path;
        VerificationProperties props = new VerificationProperties(
                List.of(new Route(svcName, baseUrl, "OAUTH_BEARER")),
                List.of("127.0.0.1"), new Retry(3, 0, 0, false),
                "cap.verification.dlq.v1", "sfdc.response.notify.v1");

        MapperRegistry mappers = new MapperRegistry();
        mappers.register(svcName, mapperPair);
        KarzaClient client = new KarzaClient(svc -> "tok-" + svc);
        VerificationService service = new VerificationService(
                new ConfigRouteResolver(props),
                new AdapterRegistry(List.of(new KarzaDomainCheckAdapter(client), new KarzaNegativeAreaAdapter(client))),
                mappers);
        VerificationDlqPort dlqPort = (req, reason) -> dlq.add(req.operation() + ":" + reason);
        SfdcNotifyPort notifyPort = (req, reason) -> notified.add(req.operation() + ":" + reason);
        VerificationDispatcher dispatcher = new VerificationDispatcher(
                service, dlqPort, notifyPort, new RetryExecutor(millis -> { }),
                RetryPolicy.idempotentReads(3, Backoff.fixed(0)));

        InMemoryBus bus = new InMemoryBus(Map.of("verification", dispatcher::handle));
        AtomicReference<JourneyDecision> decision = new AtomicReference<>();
        DecisionOutboundPort decisionPort = decision::set;
        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper()).loadFromClasspath("journeys/" + journeyFile);
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                new JourneyRegistry(List.of(def), Map.of()),
                new InMemoryJourneyInstanceStore(), bus, decisionPort, () -> "ji-s3");
        bus.bind(orchestrator);
        orchestrator.onOrigination(Map.of("type", svcName, "correlationId", "corr-" + svcName + payload.hashCode(),
                "applicationRef", "APP-1", "payload", payload));
        JourneyDecision d = decision.get();
        assertThat(d).isNotNull();
        return d;
    }

    private JourneyDecision domainCheck(Map<String, Object> payload) {
        return run("domain-check-verification.journey.json", "KARZA_DOMAIN_CHECK", "/karza/domain-check",
                new MapperPair(new KarzaDomainCheckRequestMapper(), new KarzaResourceDataResponseMapper()), payload);
    }

    private JourneyDecision negativeArea(Map<String, Object> payload) {
        return run("negative-area-verification.journey.json", "ENT_KARZA_NEGATIVE_AREA_TAGGING", "/karza/negative-area",
                new MapperPair(new KarzaNegativeAreaRequestMapper(), new KarzaResourceDataResponseMapper()), payload);
    }

    // ---- KARZA_DOMAIN_CHECK (D.2) ----
    @Test void domainCheckValidCorporateProceeds() {
        JourneyDecision d = domainCheck(Map.of("email", "john.doe@idfcfirstbank.com", "organizationName", "IDFC"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_proceed");
        assertThat(dlq).isEmpty(); assertThat(notified).isEmpty();
    }
    @Test void domainCheckDisposableEmailDeclines_businessNotTechnical() {
        JourneyDecision d = domainCheck(Map.of("email", "temp@disposable.com"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.terminalNodeId()).isEqualTo("n_decline");
        assertThat(dlq).isEmpty(); assertThat(notified).isEmpty();
    }
    @Test void domainCheckTransient503RetriesThenDlqNotify() {
        httpStatus = 503;
        JourneyDecision d = domainCheck(Map.of("email", "john.doe@idfcfirstbank.com"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(dlq).hasSize(1); assertThat(notified).hasSize(1);
    }
    @Test void domainCheckPermanent400DlqNotify() {
        httpStatus = 400;
        domainCheck(Map.of("email", "john.doe@idfcfirstbank.com"));
        assertThat(dlq.get(0)).contains("PERMANENT"); assertThat(notified).hasSize(1);
    }

    // ---- ENT_KARZA_NEGATIVE_AREA_TAGGING (D.3) ----
    @Test void negativeAreaClearProceeds() {
        JourneyDecision d = negativeArea(Map.of("addressId", "SAFE"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(d.terminalNodeId()).isEqualTo("n_proceed");
        assertThat(dlq).isEmpty(); assertThat(notified).isEmpty();
    }
    @Test void negativeAreaFlaggedDeclines_businessNotTechnical() {
        JourneyDecision d = negativeArea(Map.of("addressId", "RISK"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(d.terminalNodeId()).isEqualTo("n_decline");
        assertThat(dlq).isEmpty(); assertThat(notified).isEmpty();
    }
    @Test void negativeAreaTransient503RetriesThenDlqNotify() {
        httpStatus = 503;
        JourneyDecision d = negativeArea(Map.of("addressId", "SAFE"));
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(dlq).hasSize(1); assertThat(notified).hasSize(1);
    }
}

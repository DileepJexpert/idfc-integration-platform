package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.communications.adapter.out.meter.SemaphoreSendMeter;
import com.idfcfirstbank.integration.capabilities.communications.application.CommunicationsService;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.CommsHubPort;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SentSmsStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.OutboundNotificationMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SfdcOutboundMessage;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SfdcOutboundMessageParser;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SoapNotification;
import com.idfcfirstbank.integration.edges.sfdcingress.application.Normalizer;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyRegistry;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * END-TO-END (Docker-free) proof for the real SFDC SOAP Outbound Message, both
 * routes, one schema-agnostic edge:
 *
 * <ul>
 *   <li><b>Inbound_Wrapper</b> (account-creation) → the REAL engine starts the
 *       loan-origination journey, and the opaque business body (customerId/loanNb)
 *       arrives in the journey context INLINE — no claim-check needed.</li>
 *   <li><b>SENDSMS</b> (OTP) → the REAL {@link CommunicationsService} sends the SMS
 *       exactly once, reading Mobile__c/Description from the opaque Task body.</li>
 * </ul>
 *
 * The edge normalises uniformly (parse → un-batch → unwrap → opaque payload → route
 * by SVCNAME); each SVCNAME's OWN handler interprets the body.
 */
class SfdcSoapEndToEndTest {

    private final SfdcOutboundMessageParser parser = new SfdcOutboundMessageParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private final Map<String, RoutingDecision> routes = Map.of(
            "Inbound_Wrapper", new RoutingDecision(SourceSystem.SFDC, "Inbound_Wrapper", "orig.sfdc.pl.v1", "loan-origination"),
            "SENDSMS", new RoutingDecision(SourceSystem.SFDC, "SENDSMS", "comm.sms.send.v1", "communications"));

    /** Edge front-end: parse + un-batch + unwrap + normalise (opaque payload) each notification. */
    private List<CanonicalEnvelope> normalise(String fixtureName) throws Exception {
        return normaliseFrom(fixture(fixtureName));
    }

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/" + name)) {
            assertThat(in).as("fixture on classpath: " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<CanonicalEnvelope> normaliseFrom(String soapXml) {
        AtomicInteger corr = new AtomicInteger();
        AtomicInteger txn = new AtomicInteger();
        OutboundNotificationMapper mapper =
                new OutboundNotificationMapper(objectMapper, clock, () -> "corr-" + corr.incrementAndGet());
        Normalizer normalizer = new Normalizer(() -> "txn-" + txn.incrementAndGet());
        SfdcOutboundMessage message = parser.parse(soapXml);
        List<CanonicalEnvelope> out = new ArrayList<>();
        for (SoapNotification n : message.notifications()) {
            SfdcInboundEvent event = mapper.toEvent(n, message);
            RoutingDecision route = routes.get(event.typeCode());
            out.add(normalizer.toEnvelope(event, route, "s3://ref/" + event.notificationId(), event.correlationId()));
        }
        return out;
    }

    /** The Kafka JSON round-trip the engine consumer does, minus the broker: envelope → map. */
    private static Map<String, Object> asOriginationMap(CanonicalEnvelope e) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", e.type());
        m.put("notificationId", e.notificationId());
        m.put("orgId", e.orgId());
        m.put("sfdcRecordId", e.sfdcRecordId());
        m.put("applicationRef", e.applicationRef());
        m.put("correlationId", e.correlationId());
        m.put("source", e.source().name());
        m.put("payload", e.payload());
        return m;
    }

    @Test
    void inboundWrapperSoapRunsTheLoanOriginationJourneyWithBusinessDataInContext() throws Exception {
        CanonicalEnvelope env = normalise("sfdc-outbound-golden.xml").get(0);   // first of the batch

        JourneyDefinition def = new JourneyDefinitionLoader(new ObjectMapper())
                .loadFromClasspath("journeys/loan-origination.journey.json");
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        JourneyOrchestrator orchestrator = new JourneyOrchestrator(
                new JourneyEngine(new ExpressionEvaluator()),
                new JourneyRegistry(List.of(def), Map.of()),   // empty type map => resolves loan-origination
                store, r -> { }, d -> { }, () -> "ji-fallback");

        String id = orchestrator.onOrigination(asOriginationMap(env));

        Map<String, Object> ctx = store.find(id).orElseThrow().payload();
        // The engine started the journey AND the opaque business body reached the context inline.
        assertThat(ctx).containsKey("createGenericAccountReq");
        assertThat(ctx.toString()).contains("9900766374").contains("0008405");   // customerId / loanNb
        // Envelope identity survived alongside the opaque body.
        assertThat(ctx.get("notificationId")).isEqualTo("04l6D00000AbCdE0001");
    }

    @Test
    void sendSmsSoapRunsTheCommunicationsCapabilityAndSendsExactlyOnce() throws Exception {
        CanonicalEnvelope env = normalise("sfdc-outbound-sendsms-golden.xml").get(0);

        List<String> sentTo = new ArrayList<>();
        CommsHubPort commsHub = (to, body) -> sentTo.add(to);            // the internal shared CommsHub (mock)
        java.util.Set<String> sentRefs = new java.util.HashSet<>();
        SentSmsStorePort store = new SentSmsStorePort() {                 // markSentIfAbsent = Set.add
            @Override public boolean markSentIfAbsent(String reference) { return sentRefs.add(reference); }
            @Override public void unmark(String reference) { sentRefs.remove(reference); }
        };
        CommunicationsService comms = new CommunicationsService(
                commsHub, new SemaphoreSendMeter(4), store);            // metered send path

        Map<String, Object> smsEnvelope = asOriginationMap(env);
        comms.onSmsRequest(smsEnvelope);
        comms.onSmsRequest(smsEnvelope);   // redelivery — must not re-send the OTP

        assertThat(sentTo).as("SMS sent exactly once, to the Task's Mobile__c").containsExactly("9894873985");
    }
}

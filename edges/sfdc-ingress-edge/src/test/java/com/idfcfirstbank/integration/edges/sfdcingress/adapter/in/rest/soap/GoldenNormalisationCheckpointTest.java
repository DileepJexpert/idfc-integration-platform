package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.application.Normalizer;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHECKPOINT (normalisation spec §7): run BOTH real golden SOAP Outbound Messages —
 * a {@code createGenericAccountReq} (SVCNAME=Inbound_Wrapper) and a Salesforce
 * {@code Task} OTP send (SVCNAME=SENDSMS) — end-to-edge: parse → un-batch → unwrap
 * {@code Request__c} CDATA → map → normalise → route by SVCNAME.
 *
 * <p>The point: two STRUCTURALLY DIFFERENT bodies prove the edge is SCHEMA-AGNOSTIC —
 * it carries the entire CDATA forward as an OPAQUE {@code payload} (never reaching in
 * for {@code msgBdy} vs {@code Mobile__c}) and routes purely on the ENVELOPE's SVCNAME.
 * Prints each normalised envelope + its route for eyeball confirmation.
 */
class GoldenNormalisationCheckpointTest {

    private final SfdcOutboundMessageParser parser = new SfdcOutboundMessageParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private final AtomicInteger corrSeq = new AtomicInteger();
    private final AtomicInteger txnSeq = new AtomicInteger();

    // The SVCNAME -> route table, mirroring the edge application.yml (config-as-data).
    // Two KINDS of target: a journey (Inbound_Wrapper) and a capability action (SENDSMS).
    private final Map<String, RoutingDecision> routes = Map.of(
            "Inbound_Wrapper", new RoutingDecision(SourceSystem.SFDC, "Inbound_Wrapper",
                    "orig.sfdc.pl.v1", "loan-origination"),
            "SENDSMS", new RoutingDecision(SourceSystem.SFDC, "SENDSMS",
                    "comm.sms.send.v1", "communications"));

    private String fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/" + name)) {
            assertThat(in).as("fixture on classpath: " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private OutboundNotificationMapper mapper() {
        return new OutboundNotificationMapper(objectMapper, clock, () -> "corr-" + corrSeq.incrementAndGet());
    }

    private Normalizer normalizer() {
        return new Normalizer(() -> "txn-" + txnSeq.incrementAndGet());
    }

    /** Parse one fixture, normalise + route every notification, print, return the envelopes. */
    private java.util.List<CanonicalEnvelope> normaliseAndPrint(String fixtureName) throws Exception {
        OutboundNotificationMapper mapper = mapper();
        Normalizer normalizer = normalizer();
        SfdcOutboundMessage message = parser.parse(fixture(fixtureName));

        System.out.println("\n================ CHECKPOINT: " + fixtureName + " ================");
        System.out.println("SOAP envelope: orgId=" + message.organizationId()
                + " actionId=" + message.actionId() + " notifications=" + message.notifications().size());

        var out = new java.util.ArrayList<CanonicalEnvelope>();
        for (SoapNotification n : message.notifications()) {
            SfdcInboundEvent event = mapper.toEvent(n, message);
            RoutingDecision route = routes.get(event.typeCode());
            CanonicalEnvelope envelope = normalizer.toEnvelope(
                    event, route, "s3://claimcheck/" + event.notificationId(), event.correlationId());
            System.out.println("  SVCNAME=" + event.typeCode()
                    + "  ROUTE→ topic=" + route.topic() + " target=" + route.downstreamJourney());
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(asPrintable(envelope)));
            System.out.println("-----------------------------------------------------------------------------");
            out.add(envelope);
        }
        return out;
    }

    @Test
    void inboundWrapperAccountCreationNormalisesAndRoutesToAJourney() throws Exception {
        CanonicalEnvelope env = normaliseAndPrint("sfdc-outbound-golden.xml").get(0);

        assertThat(env.type()).isEqualTo("Inbound_Wrapper");
        assertThat(env.notificationId()).isEqualTo("04l6D00000AbCdE0001");
        assertThat(env.sfdcRecordId()).isEqualTo("a0X6D00000Rec0001");
        assertThat(env.orgId()).isEqualTo("00D6D00000020HoUAI");
        // OPAQUE: the WHOLE CDATA object is carried — the edge did NOT reach in for msgBdy.
        assertThat(env.payload()).as("opaque CDATA carried whole").containsKey("createGenericAccountReq");
        // The business fields are present but NESTED — the journey owns that navigation, not the edge.
        assertThat(env.payload().toString()).contains("9900766374").contains("0008405");
    }

    @Test
    void sendSmsOtpNormalisesAndRoutesToACapabilityAction() throws Exception {
        CanonicalEnvelope env = normaliseAndPrint("sfdc-outbound-sendsms-golden.xml").get(0);

        assertThat(env.type()).isEqualTo("SENDSMS");
        assertThat(env.notificationId()).isEqualTo("04lC4000000AbCdEAO");
        assertThat(env.sfdcRecordId()).isEqualTo("00T7F00000ABcdEFGHI");
        assertThat(env.orgId()).isEqualTo("00DC40000014dS1MAI");
        // OPAQUE: a Salesforce Task, a COMPLETELY different shape — carried whole, unmodified.
        // The edge never looked at Mobile__c/Description; the SMS capability will.
        assertThat(env.payload()).as("opaque Task body carried whole")
                .containsEntry("Type", "OTP")
                .containsEntry("Mobile__c", "9894873985")
                .containsKey("Description");
    }

    @Test
    void bothStructurallyDifferentBodiesFlowThroughTheSameSchemaAgnosticEdge() throws Exception {
        // The proof: same parse+normalise+route code handles both; each routes by SVCNAME
        // to its OWN kind of target, and neither body was interpreted by the edge.
        CanonicalEnvelope account = normaliseAndPrint("sfdc-outbound-golden.xml").get(0);
        CanonicalEnvelope sms = normaliseAndPrint("sfdc-outbound-sendsms-golden.xml").get(0);

        assertThat(routes.get(account.type()).downstreamJourney()).isEqualTo("loan-origination"); // a journey
        assertThat(routes.get(sms.type()).downstreamJourney()).isEqualTo("communications");        // a capability action
        assertThat(account.payload().keySet()).isNotEqualTo(sms.payload().keySet());               // different shapes
    }

    /** Envelope → ordered map with occurredAt stringified, so it prints without a jsr310 module. */
    private static Map<String, Object> asPrintable(CanonicalEnvelope e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionId", e.transactionId());
        m.put("schemaVersion", e.schemaVersion());
        m.put("source", e.source());
        m.put("type", e.type());
        m.put("notificationId", e.notificationId());
        m.put("orgId", e.orgId());
        m.put("sfdcRecordId", e.sfdcRecordId());
        m.put("applicationRef", e.applicationRef());
        m.put("correlationId", e.correlationId());
        m.put("payloadRef", e.payloadRef());
        m.put("occurredAt", String.valueOf(e.occurredAt()));
        m.put("payload", e.payload());   // ← the OPAQUE CDATA body, carried whole
        return m;
    }
}

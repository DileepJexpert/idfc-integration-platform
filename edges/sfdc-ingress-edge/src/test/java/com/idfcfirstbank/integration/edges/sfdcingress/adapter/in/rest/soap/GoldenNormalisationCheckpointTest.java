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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CHECKPOINT (normalisation spec §7): run the REAL golden SOAP Outbound Message
 * end-to-edge — parse → un-batch → unwrap {@code Request__c} CDATA → map →
 * normalise — and prove the business {@code msgBdy} reaches the canonical envelope
 * INLINE (not behind a claim-check ref), so the engine reads it straight into the
 * journey context. Prints each normalised envelope for eyeball confirmation.
 */
class GoldenNormalisationCheckpointTest {

    private final SfdcOutboundMessageParser parser = new SfdcOutboundMessageParser();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    // Deterministic platform ids so the printed envelope is stable/readable.
    private final AtomicInteger corrSeq = new AtomicInteger();
    private final AtomicInteger txnSeq = new AtomicInteger();

    // The SVCNAME -> journey routing the edge config resolves (Inbound_Wrapper -> loan-origination).
    private final RoutingDecision routing =
            new RoutingDecision(SourceSystem.SFDC, "Inbound_Wrapper", "orig.sfdc.pl.v1", "loan-origination");

    /** Envelope → ordered map with occurredAt stringified, so it prints without a jsr310 module. */
    private static java.util.Map<String, Object> asPrintable(CanonicalEnvelope e) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
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
        m.put("payload", e.payload());   // ← the inline business msgBdy
        return m;
    }

    private String golden() throws Exception {
        try (var in = getClass().getResourceAsStream("/sfdc-outbound-golden.xml")) {
            assertThat(in).as("golden fixture on classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void goldenSoapNormalisesToTwoEnvelopesWithBusinessDataInline() throws Exception {
        OutboundNotificationMapper mapper = new OutboundNotificationMapper(
                objectMapper, clock, () -> "corr-" + corrSeq.incrementAndGet());
        Normalizer normalizer = new Normalizer(() -> "txn-" + txnSeq.incrementAndGet());

        SfdcOutboundMessage message = parser.parse(golden());
        assertThat(message.notifications()).as("un-batched from one SOAP envelope").hasSize(2);

        System.out.println("\n================ CHECKPOINT: normalised CanonicalRequest(s) ================");
        System.out.println("SOAP envelope: orgId=" + message.organizationId()
                + " actionId=" + message.actionId() + " notifications=" + message.notifications().size() + "\n");

        for (SoapNotification n : message.notifications()) {
            SfdcInboundEvent event = mapper.toEvent(n, message);
            // Same call the ingress pipeline makes (payloadRef would be the S3 claim-check ref;
            // shown as a placeholder here — the business body rides INLINE regardless).
            CanonicalEnvelope envelope = normalizer.toEnvelope(event, routing, "s3://claimcheck/" + event.notificationId(),
                    event.correlationId());
            System.out.println(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(asPrintable(envelope)));
            System.out.println("-----------------------------------------------------------------------------");
        }

        // --- assert the business msgBdy is INLINE in each normalised envelope ---
        SfdcInboundEvent e1 = mapper.toEvent(message.notifications().get(0), message);
        CanonicalEnvelope env1 = normalizer.toEnvelope(e1, routing, "s3://ref/1", e1.correlationId());
        assertThat(env1.type()).isEqualTo("Inbound_Wrapper");
        assertThat(env1.notificationId()).isEqualTo("04l6D00000AbCdE0001");
        assertThat(env1.sfdcRecordId()).isEqualTo("a0X6D00000Rec0001");
        assertThat(env1.orgId()).isEqualTo("00D6D00000020HoUAI");
        assertThat(env1.applicationRef()).isEqualTo("00171000002cecnAAA");   // businessRef ← msgHdr.msgId
        assertThat(env1.payload()).as("business msgBdy inline").isNotNull();
        assertThat(env1.payload()).containsEntry("customerId", "9900766374");
        assertThat(env1.payload()).containsEntry("loanNb", "0008405");
        assertThat(env1.payload()).containsEntry("productType", "2000");

        SfdcInboundEvent e2 = mapper.toEvent(message.notifications().get(1), message);
        CanonicalEnvelope env2 = normalizer.toEnvelope(e2, routing, "s3://ref/2", e2.correlationId());
        assertThat(env2.notificationId()).isEqualTo("04l6D00000AbCdE0002");
        assertThat(env2.payload()).containsEntry("customerId", "9900766375");
        assertThat(env2.payload()).containsEntry("loanNb", "0008406");
    }
}

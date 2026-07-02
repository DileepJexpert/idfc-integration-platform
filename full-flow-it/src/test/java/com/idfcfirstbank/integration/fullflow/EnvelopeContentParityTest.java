package com.idfcfirstbank.integration.fullflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.idfcfirstbank.integration.digitaledge.application.DigitalNormalizer;
import com.idfcfirstbank.integration.digitaledge.application.DigitalOriginationCommand;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-edge CONTENT parity through BOTH REAL normalizers (Phase 3 item 11): the
 * same applicant data pushed through the SFDC door and the digital door must
 * arrive at the engine as (a) the identical JSON field set and (b) the IDENTICAL
 * inline payload content. The old key-set-only test could not see the digital
 * edge dropping the payload behind a fabricated claim-check ref — this one can.
 */
class EnvelopeContentParityTest {

    private static final Map<String, Object> APPLICANT =
            Map.of("pan", "ABCDE1234F", "amount", 500000, "product", "PERSONAL_LOAN");

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    private CanonicalEnvelope viaSfdcEdge() {
        SfdcInboundEvent event = new SfdcInboundEvent(
                "ntf-1", "corr-sfdc", "rec-1", "APP-1", "ORG1", "PERSONAL_LOAN",
                "{\"raw\":true}".getBytes(StandardCharsets.UTF_8), "application/json",
                clock.instant(), APPLICANT);
        RoutingDecision routing = new RoutingDecision(
                SourceSystem.SFDC, "PERSONAL_LOAN", "orig.sfdc.pl.v1", "loan-origination");
        return new Normalizer(() -> "txn-sfdc").toEnvelope(
                event, routing, "s3://idfc-claimcheck/abc", "corr-sfdc");
    }

    private CanonicalEnvelope viaDigitalEdge() {
        DigitalOriginationCommand command = new DigitalOriginationCommand(
                "CRED", "req-1", "APP-1", "PERSONAL_LOAN", "ORG1", "corr-dig", APPLICANT);
        return new DigitalNormalizer(() -> "txn-dig", clock).toEnvelope(command);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bothRealNormalizersEmitTheIdenticalJsonFieldSet() {
        Map<String, Object> sfdc = mapper.convertValue(viaSfdcEdge(), Map.class);
        Map<String, Object> digital = mapper.convertValue(viaDigitalEdge(), Map.class);
        assertThat(digital.keySet()).isEqualTo(sfdc.keySet());
    }

    @Test
    void theApplicantPayloadContentIsIdenticalThroughBothDoors() {
        CanonicalEnvelope sfdc = viaSfdcEdge();
        CanonicalEnvelope digital = viaDigitalEdge();

        assertThat(sfdc.payload())
                .as("SFDC door delivers the applicant data inline")
                .isEqualTo(APPLICANT);
        assertThat(digital.payload())
                .as("digital door delivers the SAME applicant data inline — not a dangling ref")
                .isEqualTo(APPLICANT);
        assertThat(digital.payload()).isEqualTo(sfdc.payload());
    }

    @Test
    void engineIdentityFieldsMatchAcrossDoorsExceptTheSource() {
        CanonicalEnvelope sfdc = viaSfdcEdge();
        CanonicalEnvelope digital = viaDigitalEdge();

        assertThat(sfdc.source()).isEqualTo(SourceSystem.SFDC);
        assertThat(digital.source()).isEqualTo(SourceSystem.DIGITAL);
        assertThat(digital.type()).isEqualTo(sfdc.type());
        assertThat(digital.applicationRef()).isEqualTo(sfdc.applicationRef());
        assertThat(digital.orgId()).isEqualTo(sfdc.orgId());
    }
}

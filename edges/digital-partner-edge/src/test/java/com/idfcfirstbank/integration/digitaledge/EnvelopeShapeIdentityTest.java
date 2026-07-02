package com.idfcfirstbank.integration.digitaledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.idfcfirstbank.integration.digitaledge.application.DigitalNormalizer;
import com.idfcfirstbank.integration.digitaledge.application.DigitalOriginationCommand;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE THESIS TEST. The envelope this digital edge publishes is SHAPE-IDENTICAL to
 * what the SFDC edge publishes — same shared type, same JSON field set — so the
 * engine and capabilities serve digital UNCHANGED. The only difference is
 * {@code source} (DIGITAL vs SFDC).
 *
 * <p>Parity is asserted on CONTENT, not just key sets: the applicant payload must
 * ride INLINE and reach the engine intact (a fabricated claim-check ref pointing
 * nowhere would silently discard the business data — the old bug). The cross-edge
 * both-real-normalizers content test lives in {@code full-flow-it}
 * (EnvelopeContentParityTest), which depends on both edge modules.
 */
class EnvelopeShapeIdentityTest {

    private static final Map<String, Object> APPLICANT_PAYLOAD =
            Map.of("pan", "ABCDE1234F", "amount", 500000);

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);

    private CanonicalEnvelope digitalEnvelope() {
        DigitalNormalizer normalizer = new DigitalNormalizer(() -> "txn-1", clock);
        DigitalOriginationCommand command = new DigitalOriginationCommand(
                "CRED", "req-1", "APP-1", "PERSONAL_LOAN", "ORG1", "corr-1", APPLICANT_PAYLOAD);
        return normalizer.toEnvelope(command);
    }

    /** A representative envelope as the SFDC edge produces it (the SAME shared type, inline body). */
    private CanonicalEnvelope sfdcEnvelope() {
        return new CanonicalEnvelope("txn-2", "sfdc-ingress.v1", SourceSystem.SFDC, "PERSONAL_LOAN",
                "ntf-1", "ORG1", "rec-1", "APP-1", "corr-2", "corr-2",
                "s3://idfc-claimcheck/abc", "application/json", clock.instant(), APPLICANT_PAYLOAD);
    }

    @Test
    @SuppressWarnings("unchecked")
    void digitalAndSfdcEnvelopesHaveTheIdenticalJsonShape() throws Exception {
        Map<String, Object> digital = mapper.convertValue(digitalEnvelope(), Map.class);
        Map<String, Object> sfdc = mapper.convertValue(sfdcEnvelope(), Map.class);
        assertThat(digital.keySet())
                .as("the canonical envelope field set must be identical across channels")
                .isEqualTo(sfdc.keySet());
    }

    @Test
    void onlyTheSourceDistinguishesTheChannel() {
        assertThat(digitalEnvelope().source()).isEqualTo(SourceSystem.DIGITAL);
        assertThat(sfdcEnvelope().source()).isEqualTo(SourceSystem.SFDC);
    }

    @Test
    void engineRelevantFieldsArePopulatedOnTheDigitalEnvelope() {
        CanonicalEnvelope env = digitalEnvelope();
        // The fields the engine routes/branches on must be present from the digital door.
        assertThat(env.type()).isEqualTo("PERSONAL_LOAN");
        assertThat(env.applicationRef()).isEqualTo("APP-1");
        assertThat(env.notificationId()).isEqualTo("req-1");
        assertThat(env.correlationId()).isEqualTo("corr-1");
    }

    @Test
    void applicantPayloadRidesInlineNotBehindAFabricatedClaimCheck() {
        CanonicalEnvelope env = digitalEnvelope();
        // CONTENT parity: the business data the partner sent must reach the engine.
        assertThat(env.payload())
                .as("the applicant payload must ride inline in the envelope")
                .isEqualTo(APPLICANT_PAYLOAD);
        // There is no blob store on this channel: a non-null ref would point nowhere.
        assertThat(env.payloadRef()).isNull();
    }
}

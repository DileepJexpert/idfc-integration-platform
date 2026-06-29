package com.idfcfirstbank.integration.edges.sfdcingress.parity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock.MockS3BlobStoreAdapter;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DedupeResult;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DedupeService;
import com.idfcfirstbank.integration.edges.sfdcingress.application.Normalizer;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import com.idfcfirstbank.integration.edges.sfdcingress.support.InMemoryIdempotencyStore;
import com.idfcfirstbank.integration.edges.sfdcingress.support.MutableOrgConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parity oracle harness (§F / §9). Runs RECORDED fixtures (captured
 * request -> expected-outcome pairs) through the edge's normalize + route + dedup
 * + claim-check path and compares against Mule's expected output, fetching the
 * s3Ref to compare resolved bytes. NOT a live Mule instance.
 */
class ParityOracleTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ParityOracle oracle = new ParityOracle();

    @Test
    void recordedFixtures_matchEdgeOutput_withinAllowlist() throws Exception {
        assertParity("parity/pl-new.json", true);
        assertParity("parity/lap-new.json", true);
    }

    @Test
    void outOfAllowlistDifference_failsParity_andReportsDiff() throws Exception {
        ParityResult result = runFixture("parity/pl-routing-mismatch-negative.json");
        assertThat(result.parity()).as("a real routing diff must block cutover").isFalse();
        assertThat(result.diffs()).anyMatch(d -> d.startsWith("routingTopic"));
    }

    private void assertParity(String resource, boolean expectedParity) throws Exception {
        ParityResult result = runFixture(resource);
        assertThat(result.parity())
                .as("parity for %s; diffs=%s", resource, result.diffs())
                .isEqualTo(expectedParity);
    }

    /** Run one fixture through the real edge path and compare to its expected snapshot. */
    private ParityResult runFixture(String resource) throws Exception {
        ParityFixture fixture = loadFixture(resource);

        // Seed routing-as-data and a fresh store so each fixture's verdict is deterministic.
        MutableOrgConfig orgConfig = new MutableOrgConfig()
                .route("PERSONAL_LOAN", "orig.sfdc.pl.v1")
                .route("LAP", "orig.sfdc.lap.v1")
                .knownOrg("ORG1");
        DedupeService dedupe = new DedupeService(new InMemoryIdempotencyStore(clock), clock);
        Normalizer normalizer = new Normalizer(() -> "txn-fixed"); // platform field, allowlisted
        MockS3BlobStoreAdapter blob = new MockS3BlobStoreAdapter();

        ParityFixture.Request req = fixture.request();
        byte[] payloadBytes = mapper.writeValueAsBytes(req.payload());
        SfdcInboundEvent event = new SfdcInboundEvent(req.notificationId(), req.correlationId(),
                req.sfdcRecordId(), req.applicationRef(), req.orgId(), req.type(),
                payloadBytes, "application/json", clock.instant());

        DedupeResult verdict = dedupe.resolve(event);
        RoutingDecision routing = orgConfig.resolveRouting(SourceSystem.SFDC, req.type())
                .orElse(new RoutingDecision(SourceSystem.SFDC, req.type(), "UNROUTED", "UNROUTED"));
        String ref = blob.put(payloadBytes, "application/json");
        CanonicalEnvelope envelope = normalizer.toEnvelope(event, routing, ref, req.correlationId());

        ParitySnapshot actual = new ParitySnapshot(
                verdict.path().name(), envelope.notificationId(), envelope.orgId(), envelope.type(),
                envelope.sfdcRecordId(), envelope.applicationRef(), routing.topic(), routing.downstreamJourney(),
                blob.get(envelope.payloadRef())); // RESOLVED payload (fetched via claim-check)

        ParityFixture.Expected exp = fixture.expected();
        ParitySnapshot expected = new ParitySnapshot(
                exp.dedupVerdict(), req.notificationId(), req.orgId(), req.type(),
                req.sfdcRecordId(), req.applicationRef(), exp.routingTopic(), exp.downstreamJourney(),
                mapper.writeValueAsBytes(exp.resolvedPayload()));

        return oracle.compare(expected, actual);
    }

    private ParityFixture loadFixture(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("fixture %s on classpath", resource).isNotNull();
            return mapper.readValue(in, ParityFixture.class);
        }
    }
}

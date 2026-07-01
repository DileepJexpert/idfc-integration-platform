package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.OutboundNotificationMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SfdcOutboundMessageParser;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock.MockS3BlobStoreAdapter;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.edges.sfdcingress.support.InMemoryIdempotencyStore;
import com.idfcfirstbank.integration.edges.sfdcingress.support.MutableOrgConfig;
import com.idfcfirstbank.integration.edges.sfdcingress.support.RecordingPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (Docker-free) proof of the SOAP front-end wired to the REAL ingress
 * pipeline: the golden batch un-batches into 2 canonical publishes, a resend of the
 * same batch is idempotent (per Notification/Id), an unknown SVCNAME is DLQ'd but
 * still ACKed, and a transient publish failure withholds the batch ACK.
 */
class BatchIngestServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    private RecordingPublisher publisher;
    private BatchIngestService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        SfdcOutboundMessageParser parser = new SfdcOutboundMessageParser();
        AtomicInteger seq = new AtomicInteger();
        OutboundNotificationMapper mapper =
                new OutboundNotificationMapper(objectMapper, CLOCK, () -> "corr-" + seq.incrementAndGet());

        InMemoryIdempotencyStore store = new InMemoryIdempotencyStore(CLOCK);
        MutableOrgConfig orgConfig = new MutableOrgConfig()
                .route("Inbound_Wrapper", "orig.sfdc.pl.v1")
                .knownOrg("00D6D00000020HoUAI");
        publisher = new RecordingPublisher();
        AtomicInteger txn = new AtomicInteger();
        Normalizer normalizer = new Normalizer(() -> "txn-" + txn.incrementAndGet());
        EdgePolicies policies = new EdgePolicies(5, 1);
        DedupeService dedupe = new DedupeService(store, CLOCK);
        SfdcIngressService ingress = new SfdcIngressService(
                dedupe, store, orgConfig, new MockS3BlobStoreAdapter(), publisher, normalizer, policies, CLOCK);
        service = new BatchIngestService(parser, mapper, ingress, publisher, CLOCK);
    }

    private String golden() throws Exception {
        try (var in = getClass().getResourceAsStream("/sfdc-outbound-golden.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void goldenBatchUnbatchesIntoTwoCanonicalPublishesAndAcks() throws Exception {
        BatchAck ack = service.ingestBatch(golden());

        assertThat(ack.accepted()).isTrue();
        assertThat(ack.total()).isEqualTo(2);
        assertThat(publisher.published).hasSize(2);
        assertThat(publisher.publishedTopics).containsExactly("orig.sfdc.pl.v1", "orig.sfdc.pl.v1");

        // Each published envelope carries the SVCNAME as its type and the Notification/Id.
        assertThat(publisher.published)
                .extracting(CanonicalEnvelope::type)
                .containsOnly("Inbound_Wrapper");
        assertThat(publisher.published)
                .extracting(CanonicalEnvelope::notificationId)
                .containsExactlyInAnyOrder("04l6D00000AbCdE0001", "04l6D00000AbCdE0002");
    }

    @Test
    void resendOfTheSameBatchIsIdempotent_stillOnlyTwoPublishes() throws Exception {
        service.ingestBatch(golden());
        BatchAck resend = service.ingestBatch(golden());

        assertThat(resend.accepted()).isTrue();          // duplicates ACK (idempotent)
        assertThat(publisher.published).hasSize(2);       // no double-start on redelivery
    }

    @Test
    void unknownSvcNameIsParkedInDlqButStillAcked() {
        String unknown = soapWith("Unknown_Svc", "{\"createGenericAccountReq\":{\"msgBdy\":{\"customerId\":\"1\"}}}");

        BatchAck ack = service.ingestBatch(unknown);

        assertThat(ack.accepted()).isTrue();              // permanent -> ACK, do not resend
        assertThat(publisher.published).isEmpty();
        assertThat(publisher.dlqReasons).hasSize(1);
    }

    @Test
    void malformedCdataJsonIsParkedInDlqButStillAcked() {
        String bad = soapWith("Inbound_Wrapper", "{ this is not json");

        BatchAck ack = service.ingestBatch(bad);

        assertThat(ack.accepted()).isTrue();
        assertThat(publisher.published).isEmpty();
        assertThat(publisher.dlqReasons).hasSize(1);
    }

    @Test
    void transientPublishFailureWithholdsTheBatchAck() throws Exception {
        publisher.failPublishesWith(true);

        BatchAck ack = service.ingestBatch(golden());

        assertThat(ack.accepted()).isFalse();             // -> HTTP Ack=false, SFDC resends whole batch
    }

    /** Minimal single-notification SOAP envelope for the negative cases. */
    private static String soapWith(String svcName, String requestJson) {
        return """
                <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                    xmlns="http://soap.sforce.com/2005/09/outbound"
                    xmlns:sf1="urn:sobject.enterprise.soap.sforce.com">
                  <soapenv:Body>
                    <notifications>
                      <OrganizationId>00D6D00000020HoUAI</OrganizationId>
                      <ActionId>OUTID1</ActionId>
                      <Notification>
                        <Id>04l6D00000AbCdE9999</Id>
                        <sObject>
                          <sf1:Id>a0X6D00000Rec9999</sf1:Id>
                          <sf1:CLIENTID__c>SFDC</sf1:CLIENTID__c>
                          <sf1:SVCNAME__c>%s</sf1:SVCNAME__c>
                          <sf1:VERSION__c>1.0</sf1:VERSION__c>
                          <sf1:EXECMODE__c>ASYNC</sf1:EXECMODE__c>
                          <sf1:Request__c><![CDATA[%s]]></sf1:Request__c>
                        </sObject>
                      </Notification>
                    </notifications>
                  </soapenv:Body>
                </soapenv:Envelope>
                """.formatted(svcName, requestJson);
    }
}

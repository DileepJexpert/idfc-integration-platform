package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parses the real SFDC SOAP Outbound Message golden fixture (un-batch + unwrap). */
class SfdcOutboundMessageParserTest {

    private final SfdcOutboundMessageParser parser = new SfdcOutboundMessageParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String golden() throws Exception {
        try (var in = getClass().getResourceAsStream("/sfdc-outbound-golden.xml")) {
            assertThat(in).as("golden fixture on classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void unbatchesTheEnvelopeIntoTwoNotificationsWithEnvelopeMetadata() throws Exception {
        SfdcOutboundMessage message = parser.parse(golden());

        assertThat(message.organizationId()).isEqualTo("00D6D00000020HoUAI");
        assertThat(message.actionId()).isEqualTo("OUTID1240000000000");
        assertThat(message.notifications()).hasSize(2);
    }

    @Test
    void extractsTheRoutingKeyIdsAndCdataPayloadPerNotification() throws Exception {
        SfdcOutboundMessage message = parser.parse(golden());

        SoapNotification first = message.notifications().get(0);
        assertThat(first.id()).isEqualTo("04l6D00000AbCdE0001");        // Notification/Id (dedup key)
        assertThat(first.sfdcRecordId()).isEqualTo("a0X6D00000Rec0001"); // sObject/sf1:Id (not the Notification Id)
        assertThat(first.clientId()).isEqualTo("SFDC");
        assertThat(first.svcName()).isEqualTo("Inbound_Wrapper");        // the routing key
        assertThat(first.version()).isEqualTo("1.0");
        assertThat(first.execMode()).isEqualTo("ASYNC");

        // Request__c CDATA is the inner business JSON — parseable, carrying customerId.
        var request = objectMapper.readTree(first.requestJson());
        assertThat(request.findValue("customerId").asText()).isEqualTo("9900766374");
        assertThat(request.findValue("loanNb").asText()).isEqualTo("0008405");

        SoapNotification second = message.notifications().get(1);
        assertThat(second.id()).isEqualTo("04l6D00000AbCdE0002");
        var request2 = objectMapper.readTree(second.requestJson());
        assertThat(request2.findValue("customerId").asText()).isEqualTo("9900766375");
    }

    @Test
    void unparseableEnvelopeThrows() {
        assertThatThrownBy(() -> parser.parse("<not-soap>oops"))
                .isInstanceOf(SoapParseException.class);
    }
}

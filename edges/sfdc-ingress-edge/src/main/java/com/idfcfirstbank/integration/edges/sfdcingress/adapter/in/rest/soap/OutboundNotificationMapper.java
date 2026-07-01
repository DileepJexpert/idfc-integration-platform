package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * Pure mapping: one parsed {@link SoapNotification} (+ its envelope metadata) →
 * the validated, framework-free {@link SfdcInboundEvent} the ingress pipeline
 * already runs. This is where SOAP/JSON specifics stop and the canonical event
 * begins — the engine never sees any of the SFDC shape (normalisation spec §3).
 *
 * <p>Field mapping:
 * <ul>
 *   <li>{@code notificationId} ← {@code Notification/Id} (dedup key)</li>
 *   <li>{@code sfdcRecordId}   ← {@code sObject/sf1:Id}</li>
 *   <li>{@code orgId}          ← {@code OrganizationId}</li>
 *   <li>{@code typeCode}       ← {@code SVCNAME__c} (the routing key)</li>
 *   <li>{@code applicationRef} ← inner {@code msgHdr.msgId} (stable across resend;
 *       the composite dedup fallback)</li>
 *   <li>{@code correlationId}  ← generated per request (trace only, never a dedup input)</li>
 *   <li>{@code rawPayload}     ← inner {@code msgBdy} JSON (claim-checked; the journey context input)</li>
 * </ul>
 */
public class OutboundNotificationMapper {

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Supplier<String> correlationIdSupplier;

    public OutboundNotificationMapper(ObjectMapper objectMapper, Clock clock,
                                      Supplier<String> correlationIdSupplier) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.correlationIdSupplier = correlationIdSupplier;
    }

    public SfdcInboundEvent toEvent(SoapNotification n, SfdcOutboundMessage message) {
        require(n.id(), "Notification/Id");
        require(message.organizationId(), "OrganizationId");
        require(n.svcName(), "SVCNAME__c");
        require(n.requestJson(), "Request__c");

        JsonNode request = parse(n.requestJson());
        JsonNode msgBdy = request.findValue("msgBdy");   // wrapper-name agnostic
        JsonNode businessPayload = msgBdy != null ? msgBdy : request;
        String businessRef = text(request.findValue("msgId"));

        return new SfdcInboundEvent(
                n.id(),
                correlationIdSupplier.get(),
                n.sfdcRecordId(),
                businessRef,
                message.organizationId(),
                n.svcName(),
                bytes(businessPayload),
                "application/json",
                clock.instant());
    }

    private JsonNode parse(String cdataJson) {
        try {
            return objectMapper.readTree(cdataJson);
        } catch (JsonProcessingException e) {
            throw new NotificationMappingException("Request__c is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private byte[] bytes(JsonNode node) {
        try {
            return objectMapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new NotificationMappingException("could not serialise business payload", e);
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new NotificationMappingException("missing required field: " + field);
        }
    }
}

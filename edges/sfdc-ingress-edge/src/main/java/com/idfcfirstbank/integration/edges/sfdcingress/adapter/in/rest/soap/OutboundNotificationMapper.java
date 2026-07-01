package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;

import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Pure mapping: one parsed {@link SoapNotification} (+ its envelope metadata) →
 * the validated, framework-free {@link SfdcInboundEvent} the ingress pipeline
 * already runs. This is where SOAP/JSON specifics stop and the canonical event
 * begins — the engine never sees any of the SFDC shape (normalisation spec §3).
 *
 * <p><b>The edge is SCHEMA-AGNOSTIC about the {@code Request__c} body.</b> Two real
 * messages prove why: {@code SVCNAME=Inbound_Wrapper} carries a
 * {@code createGenericAccountReq} (msgHdr/msgBdy); {@code SVCNAME=SENDSMS} carries a
 * Salesforce {@code Task} (Mobile__c/Description/Type). The CDATA JSON differs
 * completely per SVCNAME, so the edge does NOT reach into it for business fields —
 * it parses the CDATA once and carries the ENTIRE object forward as an OPAQUE
 * {@code payload}. The journey/capability that owns each SVCNAME's contract is what
 * interprets the body (account-creation reads {@code msgBdy}; the SMS capability
 * reads {@code Mobile__c}). No per-SVCNAME parsing lives here.
 *
 * <p>Field mapping (all from the ENVELOPE + SVCNAME, never from the body shape):
 * <ul>
 *   <li>{@code notificationId} ← {@code Notification/Id} (dedup key)</li>
 *   <li>{@code sfdcRecordId}   ← {@code sObject/sf1:Id}</li>
 *   <li>{@code orgId}          ← {@code OrganizationId}</li>
 *   <li>{@code typeCode}       ← {@code SVCNAME__c} (the routing key)</li>
 *   <li>{@code correlationId}  ← generated per request (trace only, never a dedup input)</li>
 *   <li>{@code businessPayload}/{@code rawPayload} ← the ENTIRE parsed {@code Request__c}
 *       CDATA, opaque — carried inline to the engine + claim-checked, never inspected</li>
 *   <li>{@code applicationRef} ← generic best-effort {@code msgId} if the body happens
 *       to carry one anywhere (optional composite-dedup fallback; dormant when absent —
 *       NOT a schema assumption, primary dedup is always {@code notificationId})</li>
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

        // Parse the CDATA once; carry the WHOLE object forward, opaque. No reach-in.
        JsonNode body = parse(n.requestJson());
        String businessRef = text(body.findValue("msgId"));   // generic optional dedup-fallback id

        return new SfdcInboundEvent(
                n.id(),
                correlationIdSupplier.get(),
                n.sfdcRecordId(),
                businessRef,
                message.organizationId(),
                n.svcName(),
                bytes(body),            // claim-check body (opaque bytes)
                "application/json",
                clock.instant(),
                asMap(body));           // SAME opaque body inline → engine's journey context
    }

    /** The parsed CDATA object as a map for inline carriage. Opaque — never inspected. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode body) {
        if (body == null || !body.isObject()) {
            return null;
        }
        return objectMapper.convertValue(body, Map.class);
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

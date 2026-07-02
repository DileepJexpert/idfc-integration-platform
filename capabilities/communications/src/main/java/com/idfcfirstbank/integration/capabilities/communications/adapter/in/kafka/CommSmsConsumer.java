package com.idfcfirstbank.integration.capabilities.communications.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.communications.application.CommunicationsService;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IN adapter: consume the SFDC edge's SENDSMS canonical envelopes and hand each to
 * the service. A send failure now PROPAGATES (retry then DLQ) instead of being
 * swallowed-and-committed, so an OTP is never silently dropped. PII discipline is
 * kept: the parse cause is NOT chained (it can echo the OTP/mobile body).
 */
@Component
public class CommSmsConsumer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final CommunicationsService service;
    private final ObjectMapper objectMapper;

    public CommSmsConsumer(CommunicationsService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{'${idfc.communications.sms-topic:comm.sms.send.v1}'.split(',')}",
            groupId = "${idfc.communications.group:communications}")
    public void onMessage(String envelopeJson) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, MAP);
        } catch (Exception e) {
            // PII: do NOT chain the parse cause (its message can echo the body) — poison to DLQ.
            throw new PoisonMessageException("comm.sms could not deserialize envelope");
        }
        service.onSmsRequest(envelope);
    }
}

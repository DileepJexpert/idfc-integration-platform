package com.idfcfirstbank.integration.capabilities.communications.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.communications.application.CommunicationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IN adapter: consume the SFDC edge's SENDSMS canonical envelopes and hand each to
 * the service. On a parse/handle failure it logs ONLY the exception CLASS — never
 * the message — because the envelope body carries the OTP/mobile (PII).
 */
@Component
public class CommSmsConsumer {

    private static final Logger log = LoggerFactory.getLogger(CommSmsConsumer.class);
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
        try {
            service.onSmsRequest(objectMapper.readValue(envelopeJson, MAP));
        } catch (Exception e) {
            // PII: do NOT log the envelope or the exception message (may echo the body).
            log.error("comm.sms could not process an envelope (cause={})", e.getClass().getName());
        }
    }
}

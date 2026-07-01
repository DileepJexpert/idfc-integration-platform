package com.idfcfirstbank.integration.sfdcresponse.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.sfdcresponse.application.SfdcResponseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Consume decisions/failure notifications and fan them out to the per-org SFDC response. */
@Component
public class SfdcNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(SfdcNotificationConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final SfdcResponseService service;
    private final ObjectMapper objectMapper;

    public SfdcNotificationConsumer(SfdcResponseService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${idfc.sfdc-response.notify-topic:sfdc.response.notify.v1}",
            groupId = "${idfc.sfdc-response.group:sfdc-response}")
    public void onMessage(String json) {
        try {
            service.onNotification(objectMapper.readValue(json, MAP));
        } catch (Exception e) {
            log.error("sfdc-response could not process a notification (cause={})", e.getClass().getName());
        }
    }
}

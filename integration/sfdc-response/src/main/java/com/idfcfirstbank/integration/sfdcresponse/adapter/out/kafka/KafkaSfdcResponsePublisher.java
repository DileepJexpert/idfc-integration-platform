package com.idfcfirstbank.integration.sfdcresponse.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.sfdcresponse.domain.model.OrgResponse;
import com.idfcfirstbank.integration.sfdcresponse.domain.port.out.SfdcResponsePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Publishes the per-org SFDC response to its configured topic (a real SFDC callback is a later slice). */
@Component
public class KafkaSfdcResponsePublisher implements SfdcResponsePort {

    private static final Logger log = LoggerFactory.getLogger(KafkaSfdcResponsePublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public KafkaSfdcResponsePublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    @Override
    public void deliver(OrgResponse target, Map<String, Object> notification) {
        try {
            String key = String.valueOf(notification.get("correlationId"));
            kafka.send(target.responseTopic(), key, objectMapper.writeValueAsString(notification));
        } catch (JsonProcessingException e) {
            log.error("sfdc-response.serialise-failed topic={} (cause={})",
                    target.responseTopic(), e.getClass().getName());
        }
    }
}

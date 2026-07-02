package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateEventPort;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Emits the {@code MandateCallback} domain event to Kafka, keyed by invoiceNo —
 * what a journey {@code wait} node correlates on (engine wait is T3; the
 * capability emits the event today, BRD §2).
 */
@Component
public class KafkaMandateEventPublisher implements MandateEventPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaMandateEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaMandateEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                      @Value("${idfc.mandate.callback-topic:mandate.callback.v1}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void emitMandateCallback(String invoiceNo, Map<String, Object> event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("could not serialise MandateCallback for invoiceNo={}", invoiceNo, e);
            return;
        }
        // Confirm delivery: a lost MandateCallback must NOT be silently swallowed —
        // a KafkaPublishException propagates to the caller.
        KafkaDelivery.confirm(kafkaTemplate.send(topic, invoiceNo, json));
    }
}

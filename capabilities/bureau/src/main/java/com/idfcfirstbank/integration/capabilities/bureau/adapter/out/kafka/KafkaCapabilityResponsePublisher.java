package com.idfcfirstbank.integration.capabilities.bureau.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * OUT adapter: publish the response as JSON to {@code cap.<key>.response.v1}
 * (topic derived from the response's capabilityKey).
 */
public class KafkaCapabilityResponsePublisher implements CapabilityResponsePort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaCapabilityResponsePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CapabilityResponse response) {
        String topic = CapabilityTopics.response(response.capabilityKey());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable response for node " + response.nodeId(), e);
        }
        KafkaDelivery.confirm(kafkaTemplate.send(topic, response.journeyInstanceId(), payload));
    }
}

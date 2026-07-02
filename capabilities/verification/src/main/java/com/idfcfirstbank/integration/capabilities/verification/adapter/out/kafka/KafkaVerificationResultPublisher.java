package com.idfcfirstbank.integration.capabilities.verification.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes the capability result to {@code cap.verification.response.v1} (engine consumes it). */
@Component
public class KafkaVerificationResultPublisher {

    private static final String RESPONSE_TOPIC = CapabilityTopics.response("verification");

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;

    public KafkaVerificationResultPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
    }

    public void publish(CapabilityResponse response) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise capability response", e);
        }
        KafkaDelivery.confirm(kafka.send(RESPONSE_TOPIC, response.journeyInstanceId(), payload));
    }
}

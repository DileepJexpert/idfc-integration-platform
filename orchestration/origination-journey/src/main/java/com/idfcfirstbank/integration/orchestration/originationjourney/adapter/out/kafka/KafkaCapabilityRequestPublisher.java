package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes a {@link CapabilityRequest} as JSON to {@code cap.<key>.request.v1}
 * (the topic derived from the request's capabilityKey via {@link CapabilityTopics}).
 * Keyed by journeyInstanceId so one run's messages keep order on a partition.
 */
public class KafkaCapabilityRequestPublisher implements CapabilityRequestPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaCapabilityRequestPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CapabilityRequest request) {
        String topic = CapabilityTopics.request(request.capabilityKey());
        try {
            kafkaTemplate.send(topic, request.journeyInstanceId(), objectMapper.writeValueAsString(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable capability request for node " + request.nodeId(), e);
        }
    }
}

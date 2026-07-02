package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Publishes the final {@link JourneyDecision} as JSON to the decision topic the
 * edge consumes for its SFDC push-back (topic name from config).
 */
public class KafkaDecisionPublisher implements DecisionOutboundPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String decisionTopic;

    public KafkaDecisionPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                  String decisionTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.decisionTopic = decisionTopic;
    }

    @Override
    public void publish(JourneyDecision decision) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(decision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable decision for instance " + decision.journeyInstanceId(), e);
        }
        // Confirm delivery: the decision that closes the loop to SFDC must not be lost silently.
        KafkaDelivery.confirm(kafkaTemplate.send(decisionTopic, decision.applicationRef(), payload));
    }
}

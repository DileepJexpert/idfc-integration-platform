package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consumes EVERY capability response via the topic pattern
 * {@code cap\..*\.response\.v1} — one listener catches all capabilities, so a new
 * capability needs no engine code change. Each response advances its journey.
 */
@Component
public class CapabilityResponseConsumer {

    private static final Logger log = LoggerFactory.getLogger(CapabilityResponseConsumer.class);

    private final JourneyOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public CapabilityResponseConsumer(JourneyOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topicPattern = "${idfc.engine.response-topic-pattern:cap\\..*\\.response\\.v1}",
            groupId = "${idfc.engine.response-group:origination-journey-engine}")
    public void onMessage(String responseJson) {
        try {
            orchestrator.onCapabilityResponse(objectMapper.readValue(responseJson, CapabilityResponse.class));
        } catch (Exception e) {
            log.error("engine.response could not process capability response: {}", responseJson, e);
        }
    }
}

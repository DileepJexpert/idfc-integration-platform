package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consumes EVERY capability response via the topic pattern
 * {@code cap\..*\.response\.v1} — one listener catches all capabilities, so a new
 * capability needs no engine code change. Each response advances its journey.
 */
@Component
public class CapabilityResponseConsumer {

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
        CapabilityResponse response;
        try {
            response = objectMapper.readValue(responseJson, CapabilityResponse.class);
        } catch (Exception e) {
            // Undeserializable response can never advance a journey: to the DLQ, no retry.
            throw new PoisonMessageException("engine could not deserialize capability response", e);
        }
        // A store/publish/engine failure now PROPAGATES to the container error handler
        // (retry then DLQ) instead of being swallowed — a lost response no longer
        // silently strands the journey in RUNNING.
        orchestrator.onCapabilityResponse(response);
    }
}

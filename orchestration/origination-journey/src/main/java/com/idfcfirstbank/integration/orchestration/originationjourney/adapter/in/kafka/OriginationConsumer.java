package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IN adapter: consumes canonical origination envelopes the edge publishes and
 * starts a journey for each. Envelope topics come from config
 * ({@code idfc.engine.origination-topics}).
 */
@Component
public class OriginationConsumer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JourneyOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public OriginationConsumer(JourneyOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{'${idfc.engine.origination-topics:orig.sfdc.pl.v1}'.split(',')}",
            groupId = "${idfc.engine.origination-group:origination-journey-engine}")
    public void onMessage(String envelopeJson) {
        Map<String, Object> envelope;
        try {
            envelope = objectMapper.readValue(envelopeJson, MAP);
        } catch (Exception e) {
            // Undeserializable envelope can never start a journey: to the DLQ, no retry.
            throw new PoisonMessageException("engine could not deserialize origination envelope", e);
        }
        // A start failure (store/publish) now PROPAGATES to the container error handler
        // (retry then DLQ) instead of being swallowed-and-committed.
        orchestrator.onOrigination(envelope);
    }
}

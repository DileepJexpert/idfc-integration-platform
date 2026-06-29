package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(OriginationConsumer.class);
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
        try {
            orchestrator.onOrigination(objectMapper.readValue(envelopeJson, MAP));
        } catch (Exception e) {
            log.error("engine.origination could not process envelope: {}", envelopeJson, e);
        }
    }
}

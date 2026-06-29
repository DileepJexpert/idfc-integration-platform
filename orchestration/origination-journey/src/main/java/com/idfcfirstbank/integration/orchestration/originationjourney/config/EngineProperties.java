package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Engine configuration (config-as-data). Journeys, the decision topic, and the
 * businessLine({@code type}) -> journey routing are all data, not code.
 */
@ConfigurationProperties(prefix = "idfc.engine")
public record EngineProperties(
        List<String> journeyResources,
        String decisionTopic,
        Map<String, String> typeToJourney) {

    public EngineProperties {
        if (journeyResources == null || journeyResources.isEmpty()) {
            journeyResources = List.of("journeys/loan-origination.journey.json");
        }
        if (decisionTopic == null || decisionTopic.isBlank()) {
            decisionTopic = "orig.decision.v1";
        }
        if (typeToJourney == null) {
            typeToJourney = Map.of();
        }
    }
}

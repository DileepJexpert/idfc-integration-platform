package com.idfcfirstbank.integration.capabilities.scoring.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.scoring.application.ScoringService;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.scoring.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class ScoringRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScoringRequestConsumer.class);

    private final ScoringService service;
    private final CapabilityResponsePort responsePort;
    private final ObjectMapper objectMapper;

    public ScoringRequestConsumer(ScoringService service, CapabilityResponsePort responsePort,
                                  ObjectMapper objectMapper) {
        this.service = service;
        this.responsePort = responsePort;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{T(com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics).request('scoring')}",
            groupId = "${idfc.capability.group:scoring}")
    public void onMessage(String requestJson) {
        try {
            CapabilityRequest request = objectMapper.readValue(requestJson, CapabilityRequest.class);
            responsePort.publish(service.handle(request));
        } catch (Exception e) {
            log.error("scoring could not process request: {}", requestJson, e);
        }
    }
}

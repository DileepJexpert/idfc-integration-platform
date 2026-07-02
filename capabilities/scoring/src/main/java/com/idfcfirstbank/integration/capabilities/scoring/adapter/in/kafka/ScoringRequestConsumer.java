package com.idfcfirstbank.integration.capabilities.scoring.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.scoring.application.ScoringService;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.scoring.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class ScoringRequestConsumer {

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
        CapabilityRequest request;
        try {
            request = objectMapper.readValue(requestJson, CapabilityRequest.class);
        } catch (Exception e) {
            // Undeserializable input can never succeed: straight to <topic>.dlq, no retry.
            throw new PoisonMessageException("scoring could not deserialize capability request", e);
        }
        // Processing/publish failures propagate to the container error handler
        // (retry with backoff, then dead-letter) — never swallowed-and-committed.
        responsePort.publish(service.handle(request));
    }
}

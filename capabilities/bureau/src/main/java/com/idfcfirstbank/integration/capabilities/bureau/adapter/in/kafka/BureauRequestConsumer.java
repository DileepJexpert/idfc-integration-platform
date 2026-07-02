package com.idfcfirstbank.integration.capabilities.bureau.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.bureau.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class BureauRequestConsumer {

    private final BureauService service;
    private final CapabilityResponsePort responsePort;
    private final ObjectMapper objectMapper;

    public BureauRequestConsumer(BureauService service, CapabilityResponsePort responsePort,
                                 ObjectMapper objectMapper) {
        this.service = service;
        this.responsePort = responsePort;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{T(com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics).request('bureau')}",
            groupId = "${idfc.capability.group:bureau}")
    public void onMessage(String requestJson) {
        CapabilityRequest request;
        try {
            request = objectMapper.readValue(requestJson, CapabilityRequest.class);
        } catch (Exception e) {
            // Undeserializable input can never succeed: straight to <topic>.dlq, no retry.
            throw new PoisonMessageException("bureau could not deserialize capability request", e);
        }
        // Processing/publish failures propagate to the container error handler
        // (retry with backoff, then dead-letter) — never swallowed-and-committed.
        responsePort.publish(service.handle(request));
    }
}

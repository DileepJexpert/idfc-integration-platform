package com.idfcfirstbank.integration.capabilities.customer.party.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.customer.party.application.CustomerPartyService;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.customer-party.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class CustomerPartyRequestConsumer {

    private final CustomerPartyService service;
    private final CapabilityResponsePort responsePort;
    private final ObjectMapper objectMapper;

    public CustomerPartyRequestConsumer(CustomerPartyService service, CapabilityResponsePort responsePort,
                                        ObjectMapper objectMapper) {
        this.service = service;
        this.responsePort = responsePort;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{T(com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics).request('customer-party')}",
            groupId = "${idfc.capability.group:customer-party}")
    public void onMessage(String requestJson) {
        CapabilityRequest request;
        try {
            request = objectMapper.readValue(requestJson, CapabilityRequest.class);
        } catch (Exception e) {
            // Undeserializable input can never succeed: straight to <topic>.dlq, no retry.
            throw new PoisonMessageException("customer-party could not deserialize capability request", e);
        }
        // Processing/publish failures propagate to the container error handler
        // (retry with backoff, then dead-letter) — never swallowed-and-committed.
        responsePort.publish(service.handle(request));
    }
}

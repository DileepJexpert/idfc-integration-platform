package com.idfcfirstbank.integration.capabilities.customer.party.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.customer.party.application.CustomerPartyService;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.customer-party.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class CustomerPartyRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(CustomerPartyRequestConsumer.class);

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
        try {
            CapabilityRequest request = objectMapper.readValue(requestJson, CapabilityRequest.class);
            responsePort.publish(service.handle(request));
        } catch (Exception e) {
            log.error("customer-party could not process request: {}", requestJson, e);
        }
    }
}

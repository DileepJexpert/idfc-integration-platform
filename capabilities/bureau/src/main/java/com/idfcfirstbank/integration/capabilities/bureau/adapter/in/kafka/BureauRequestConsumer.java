package com.idfcfirstbank.integration.capabilities.bureau.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.bureau.request.v1}, invoke the service,
 * publish the response. JSON String serde, matching the engine.
 */
@Component
public class BureauRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(BureauRequestConsumer.class);

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
        try {
            CapabilityRequest request = objectMapper.readValue(requestJson, CapabilityRequest.class);
            responsePort.publish(service.handle(request));
        } catch (Exception e) {
            log.error("bureau could not process request: {}", requestJson, e);
        }
    }
}

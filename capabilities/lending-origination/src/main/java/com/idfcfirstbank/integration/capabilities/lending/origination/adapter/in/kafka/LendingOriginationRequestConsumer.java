package com.idfcfirstbank.integration.capabilities.lending.origination.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.lending.origination.application.LendingOriginationService;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.lending-origination.request.v1}, invoke the
 * service, publish the response. JSON String serde, matching the engine.
 */
@Component
public class LendingOriginationRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(LendingOriginationRequestConsumer.class);

    private final LendingOriginationService service;
    private final CapabilityResponsePort responsePort;
    private final ObjectMapper objectMapper;

    public LendingOriginationRequestConsumer(LendingOriginationService service, CapabilityResponsePort responsePort,
                                             ObjectMapper objectMapper) {
        this.service = service;
        this.responsePort = responsePort;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{T(com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics).request('lending-origination')}",
            groupId = "${idfc.capability.group:lending-origination}")
    public void onMessage(String requestJson) {
        try {
            CapabilityRequest request = objectMapper.readValue(requestJson, CapabilityRequest.class);
            responsePort.publish(service.handle(request));
        } catch (Exception e) {
            log.error("lending-origination could not process request: {}", requestJson, e);
        }
    }
}

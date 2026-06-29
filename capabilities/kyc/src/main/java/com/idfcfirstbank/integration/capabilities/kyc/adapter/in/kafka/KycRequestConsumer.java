package com.idfcfirstbank.integration.capabilities.kyc.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.kyc.application.KycService;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume {@code cap.kyc.request.v1}, invoke the service, publish the
 * response. JSON String serde, matching the engine.
 */
@Component
public class KycRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(KycRequestConsumer.class);

    private final KycService service;
    private final CapabilityResponsePort responsePort;
    private final ObjectMapper objectMapper;

    public KycRequestConsumer(KycService service, CapabilityResponsePort responsePort,
                              ObjectMapper objectMapper) {
        this.service = service;
        this.responsePort = responsePort;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "#{T(com.idfcfirstbank.integration.shared.domain.capability.CapabilityTopics).request('kyc')}",
            groupId = "${idfc.capability.group:kyc}")
    public void onMessage(String requestJson) {
        try {
            CapabilityRequest request = objectMapper.readValue(requestJson, CapabilityRequest.class);
            responsePort.publish(service.handle(request));
        } catch (Exception e) {
            log.error("kyc could not process request: {}", requestJson, e);
        }
    }
}

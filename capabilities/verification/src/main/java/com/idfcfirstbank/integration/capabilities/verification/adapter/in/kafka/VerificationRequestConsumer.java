package com.idfcfirstbank.integration.capabilities.verification.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.kafka.KafkaVerificationResultPublisher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume verification requests, run the retry/DLQ dispatcher, publish the
 * result. Classified failures are handled ENTIRELY in-app by the dispatcher (retry →
 * in-app DLQ + notify); only what the in-app path cannot cover reaches the shared
 * container error handler: an undeserializable record (poison → raw-record DLQ; the
 * in-app DLQ needs a PARSED request) and a publish failure of the result itself.
 * PII: the poison cause is NOT chained — its message can echo the body.
 */
@Component
public class VerificationRequestConsumer {

    private final VerificationDispatcher dispatcher;
    private final KafkaVerificationResultPublisher resultPublisher;
    private final ObjectMapper objectMapper;

    public VerificationRequestConsumer(VerificationDispatcher dispatcher,
                                       KafkaVerificationResultPublisher resultPublisher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.resultPublisher = resultPublisher;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${idfc.verification.request-topic:cap.verification.request.v1}",
            groupId = "${idfc.verification.group:cap-verification}")
    public void onMessage(String requestJson) {
        CapabilityRequest request;
        try {
            request = objectMapper.readValue(requestJson, CapabilityRequest.class);
        } catch (Exception e) {
            throw new PoisonMessageException(
                    "verification could not deserialize request (cause=" + e.getClass().getName() + ")");
        }
        CapabilityResponse response = dispatcher.handle(request);   // retry + DLQ inside
        resultPublisher.publish(response);
    }
}

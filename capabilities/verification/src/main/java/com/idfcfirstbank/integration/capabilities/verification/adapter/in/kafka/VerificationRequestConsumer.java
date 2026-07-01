package com.idfcfirstbank.integration.capabilities.verification.adapter.in.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.adapter.out.kafka.KafkaVerificationResultPublisher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * IN adapter: consume verification requests, run the retry/DLQ dispatcher, publish the
 * result. On a deserialisation failure it logs ONLY the exception class — never the body
 * (reg no / email / account no are PII).
 */
@Component
public class VerificationRequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(VerificationRequestConsumer.class);

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
            log.error("verify.request.unparseable (cause={})", e.getClass().getName());
            return;
        }
        CapabilityResponse response = dispatcher.handle(request);   // retry + DLQ inside
        resultPublisher.publish(response);
    }
}

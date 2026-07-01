package com.idfcfirstbank.integration.capabilities.verification.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Dead-letters a failed verification to {@code cap.verification.dlq.v1} — durable and
 * replayable (correction #2: never silent-ack/lose). The full request rides in the DLQ
 * record (so it can be replayed); only ids + reason are LOGGED, never the payload (PII).
 */
@Component
public class KafkaVerificationDlqPublisher implements VerificationDlqPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaVerificationDlqPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String dlqTopic;

    public KafkaVerificationDlqPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                         VerificationProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.dlqTopic = properties.dlqTopic();
    }

    @Override
    public void deadLetter(CapabilityRequest request, String reason) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    dlqTopic, request.journeyInstanceId(), objectMapper.writeValueAsString(request));
            record.headers().add(new RecordHeader("dlqReason", reason.getBytes(StandardCharsets.UTF_8)));
            kafka.send(record);
            // PII: log ids + reason only — NEVER the payload (reg no / email / account no).
            log.error("verify.dlq.published svcName={} correlationId={} reason={}",
                    request.operation(), request.correlationId(), reason);
        } catch (JsonProcessingException e) {
            log.error("verify.dlq.serialise-failed svcName={} correlationId={} (cause={})",
                    request.operation(), request.correlationId(), e.getClass().getName());
        }
    }
}

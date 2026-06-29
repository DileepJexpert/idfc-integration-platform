package com.idfcfirstbank.integration.digitaledge.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.digitaledge.domain.port.EnvelopePublisherPort;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;

/**
 * Publishes the SHARED canonical envelope (JSON) to the SAME origination topic
 * the engine consumes — byte-shape-identical to the SFDC edge's output. Keyed by
 * notificationId; correlation ids ride as headers. Synchronous send so a broker
 * failure throws (transient → the partner retries).
 */
public class KafkaEnvelopePublisher implements EnvelopePublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaEnvelopePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaEnvelopePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void publish(CanonicalEnvelope envelope, String topic) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, envelope.notificationId(), toJson(envelope));
        record.headers().add("correlationId", nullSafe(envelope.correlationId()));
        record.headers().add("notificationId", nullSafe(envelope.notificationId()));
        record.headers().add("source", nullSafe(envelope.source().name()));
        try {
            kafkaTemplate.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to " + topic, e);
        } catch (ExecutionException e) {
            log.error("digital.publish-failed topic={} notificationId={}", topic, envelope.notificationId(), e);
            throw new IllegalStateException("publish to " + topic + " failed", e);
        }
    }

    private String toJson(CanonicalEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable canonical envelope", e);
        }
    }

    private static byte[] nullSafe(String v) {
        return (v == null ? "" : v).getBytes(StandardCharsets.UTF_8);
    }
}

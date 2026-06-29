package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.config.EdgeProperties;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * The real Kafka publisher of the canonical envelope (origination transport).
 * The envelope is serialized to JSON and keyed by {@code notificationId} so one
 * application's events keep order on a partition; correlation/notification ids
 * (and {@code resendOf} on a resend) ride as headers.
 *
 * <p>The send is synchronous: a broker failure throws so the ingress service can
 * classify it as transient (C2 — do NOT ACK; let SFDC redeliver).
 */
@Component
public class KafkaMessagePublisher implements MessagePublisherPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessagePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String dlqTopic;

    public KafkaMessagePublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                 EdgeProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.dlqTopic = properties.dlqTopic();
    }

    @Override
    public void publish(CanonicalEnvelope envelope, RoutingDecision routing, Map<String, String> headers) {
        send(routing.topic(), envelope, headers);
    }

    @Override
    public void publishToDlq(CanonicalEnvelope envelope, Map<String, String> headers, String reason) {
        Map<String, String> withReason = new java.util.LinkedHashMap<>(headers == null ? Map.of() : headers);
        withReason.put("dlqReason", reason);
        send(dlqTopic, envelope, withReason);
    }

    private void send(String topic, CanonicalEnvelope envelope, Map<String, String> headers) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(topic, envelope.notificationId(), toJson(envelope));
        if (headers != null) {
            headers.forEach((k, v) -> {
                if (v != null) {
                    record.headers().add(k, v.getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        try {
            // Synchronous: surface a broker failure as a transient error to the caller.
            kafkaTemplate.send(record).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted publishing to " + topic, e);
        } catch (ExecutionException e) {
            log.error("edge.publish-failed topic={} notificationId={}", topic, envelope.notificationId(), e);
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
}

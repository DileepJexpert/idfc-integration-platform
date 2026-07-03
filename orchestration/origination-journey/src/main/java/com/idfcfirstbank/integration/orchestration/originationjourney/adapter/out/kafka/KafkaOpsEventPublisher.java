package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.OpsEvent;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.OpsEventPort;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes {@link OpsEvent}s as JSON to {@code ops.journey.events.v1}, keyed by
 * journeyInstanceId (one run's events stay ordered on a partition). The send is
 * CONFIRMED — a failure is never silent — but it is also never fatal: a broker
 * blip must not fail a loan hop for the sake of observability, so a failed emit
 * is logged + counted and DROPPED (the ops API reads the store, not this topic).
 */
public class KafkaOpsEventPublisher implements OpsEventPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOpsEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final AtomicLong dropped = new AtomicLong();

    public KafkaOpsEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                  ObjectMapper objectMapper, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void emit(OpsEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            KafkaDelivery.confirm(kafkaTemplate.send(topic, event.journeyInstanceId(), payload));
        } catch (Exception e) {
            // Known, counted, never propagated — ids only in the log line.
            long total = dropped.incrementAndGet();
            log.error("ops.event.dropped event={} instanceId={} droppedTotal={}: {}",
                    event.event(), event.journeyInstanceId(), total, e.toString());
        }
    }

    long droppedCount() {
        return dropped.get();
    }
}

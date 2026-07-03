package com.idfcfirstbank.integration.demo.fusionhcm.batch;

import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Confirmed sends (P1.2) — the demo edge follows the house producer rule. */
@Component
public class KafkaEnvelopePublisher implements EnvelopePublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEnvelopePublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publish(String topic, String key, String envelopeJson) {
        KafkaDelivery.confirm(kafkaTemplate.send(topic, key, envelopeJson));
    }
}

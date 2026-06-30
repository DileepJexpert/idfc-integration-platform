package com.idfcfirstbank.integration.brandrouter.domain;

/** Produce the routed message to the Kafka response topic (keyed by brand). */
public interface KafkaResponsePort {
    void send(String key, String payload);
}

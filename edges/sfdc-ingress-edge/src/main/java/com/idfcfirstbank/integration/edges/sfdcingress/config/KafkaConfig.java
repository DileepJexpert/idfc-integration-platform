package com.idfcfirstbank.integration.edges.sfdcingress.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

import java.util.ArrayList;
import java.util.List;

/**
 * Kafka wiring. The {@code boundedFinnOneListenerFactory} caps the harness
 * consumer's concurrency at N; origination topics are created with N partitions
 * so N records can be in flight at once and a burst beyond that becomes Kafka
 * consumer lag (drains to zero afterwards) — the §G backpressure property.
 */
@Configuration
public class KafkaConfig {

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> boundedFinnOneListenerFactory(
            ConsumerFactory<String, String> consumerFactory, EdgeProperties properties) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // Bounded pool: at most N consumer threads => at most N concurrent FinnOne calls.
        factory.setConcurrency(properties.finnoneMaxConcurrency());
        return factory;
    }

    /** Auto-create the seed origination topics (N partitions) + DLQ (1 partition). */
    @Bean
    KafkaAdmin.NewTopics edgeTopics(EdgeProperties properties) {
        int partitions = properties.finnoneMaxConcurrency();
        List<NewTopic> topics = new ArrayList<>();
        properties.routing().forEach(rule ->
                topics.add(TopicBuilder.name(rule.topic()).partitions(partitions).replicas(1).build()));
        topics.add(TopicBuilder.name(properties.dlqTopic()).partitions(1).replicas(1).build());
        return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
    }
}

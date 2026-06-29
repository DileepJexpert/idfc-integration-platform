package com.idfcfirstbank.integration.digitaledge.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.digitaledge.adapter.out.aerospike.AerospikeIdempotencyGate;
import com.idfcfirstbank.integration.digitaledge.adapter.out.kafka.KafkaEnvelopePublisher;
import com.idfcfirstbank.integration.digitaledge.application.ApplicationStatusStore;
import com.idfcfirstbank.integration.digitaledge.application.DigitalIngressService;
import com.idfcfirstbank.integration.digitaledge.application.DigitalNormalizer;
import com.idfcfirstbank.integration.digitaledge.application.OriginationRouting;
import com.idfcfirstbank.integration.digitaledge.domain.port.EnvelopePublisherPort;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wires the framework-free digital ingress to its ports. The Aerospike gate and
 * the Kafka publisher are exposed only through their ports; routing comes from
 * config-as-data (the SAME topics the SFDC edge uses).
 */
@Configuration
@EnableConfigurationProperties(DigitalEdgeProperties.class)
public class DigitalEdgeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    Supplier<String> transactionIdSupplier() {
        return () -> "txn-" + UUID.randomUUID();
    }

    @Bean
    PartnerRegistry partnerRegistry(DigitalEdgeProperties properties) {
        return new PartnerRegistry(properties);
    }

    @Bean
    OriginationRouting originationRouting(DigitalEdgeProperties properties) {
        Map<String, String> byType = new LinkedHashMap<>();
        properties.routing().forEach(rule -> byType.put(rule.type(), rule.topic()));
        return type -> Optional.ofNullable(byType.get(type));
    }

    @Bean
    DigitalNormalizer digitalNormalizer(Supplier<String> transactionIdSupplier, Clock clock) {
        return new DigitalNormalizer(transactionIdSupplier, clock);
    }

    @Bean
    DigitalIngressService digitalIngressService(IdempotencyGatePort gate, EnvelopePublisherPort publisher,
                                                OriginationRouting routing, DigitalNormalizer normalizer,
                                                ApplicationStatusStore statusStore) {
        return new DigitalIngressService(gate, publisher, routing, normalizer, statusStore);
    }

    // --- Aerospike (the SAME platform store) -------------------------------------

    @Bean(destroyMethod = "close")
    IAerospikeClient aerospikeClient(DigitalEdgeProperties properties) {
        ClientPolicy policy = new ClientPolicy();
        policy.failIfNotConnected = false; // don't block startup if the cluster isn't up yet
        policy.timeout = 3000;
        return new AerospikeClient(policy, properties.aerospike().host(), properties.aerospike().port());
    }

    @Bean
    IdempotencyGatePort idempotencyGatePort(IAerospikeClient client, DigitalEdgeProperties properties) {
        var aero = properties.aerospike();
        return new AerospikeIdempotencyGate(client, aero.namespace(), aero.notificationSet(),
                aero.applicationSet(), aero.ttlSeconds());
    }

    // --- Kafka producer ----------------------------------------------------------

    @Bean
    ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    EnvelopePublisherPort envelopePublisherPort(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaEnvelopePublisher(kafkaTemplate, objectMapper);
    }
}

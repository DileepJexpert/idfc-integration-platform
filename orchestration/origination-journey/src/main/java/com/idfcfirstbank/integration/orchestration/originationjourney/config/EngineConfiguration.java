package com.idfcfirstbank.integration.orchestration.originationjourney.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka.KafkaCapabilityRequestPublisher;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.kafka.KafkaDecisionPublisher;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.AerospikeJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyOrchestrator;
import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyRegistry;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.CapabilityRequestPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.ExpressionEvaluator;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.service.JourneyEngine;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Wires the framework-free engine to its ports. The concrete Kafka publishers and
 * the in-memory store are created here and exposed ONLY through their ports, so
 * swapping (e.g. an Aerospike instance store) later is a move, not a rewrite.
 */
@Configuration
@EnableConfigurationProperties(EngineProperties.class)
@EnableScheduling
public class EngineConfiguration {

    @Bean
    ExpressionEvaluator expressionEvaluator() {
        return new ExpressionEvaluator();
    }

    /** Clock for the liveness sweeper (overridable in tests). */
    @Bean
    Clock engineClock() {
        return Clock.systemUTC();
    }

    @Bean
    JourneyEngine journeyEngine(ExpressionEvaluator evaluator) {
        return new JourneyEngine(evaluator);
    }

    @Bean
    JourneyDefinitionLoader journeyDefinitionLoader(ObjectMapper objectMapper) {
        return new JourneyDefinitionLoader(objectMapper);
    }

    @Bean
    JourneyRegistry journeyRegistry(JourneyDefinitionLoader loader, EngineProperties props) {
        List<JourneyDefinition> definitions = props.journeyResources().stream()
                .map(loader::loadFromClasspath)
                .toList();
        return new JourneyRegistry(definitions, props.typeToJourney());
    }

    /**
     * Journey-instance store: durable Aerospike when {@code idfc.engine.state-store
     * =aerospike} (per ARCHITECTURE_origination-journey), else the in-memory default
     * (Docker-free). Exposed only as the port, so it is a swap, not a rewrite.
     */
    @Bean
    JourneyInstanceStore journeyInstanceStore(EngineProperties props, ObjectMapper objectMapper) {
        if (props.usesAerospikeState()) {
            var aero = props.aerospike();
            var policy = new com.aerospike.client.policy.ClientPolicy();
            policy.failIfNotConnected = false;
            policy.timeout = 3000;
            var client = new com.aerospike.client.AerospikeClient(policy, aero.host(), aero.port());
            return new AerospikeJourneyInstanceStore(client, objectMapper, aero.namespace(),
                    aero.instanceSet(), aero.ttlSeconds());
        }
        return new InMemoryJourneyInstanceStore();
    }

    @Bean
    Supplier<String> instanceIdSupplier() {
        return () -> "ji-" + UUID.randomUUID();
    }

    // --- Kafka producer (String JSON payloads) ----------------------------------

    @Bean
    ProducerFactory<String, String> producerFactory(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // --- OUT ports --------------------------------------------------------------

    @Bean
    CapabilityRequestPort capabilityRequestPort(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaCapabilityRequestPublisher(kafkaTemplate, objectMapper);
    }

    @Bean
    DecisionOutboundPort decisionOutboundPort(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                                              EngineProperties props) {
        return new KafkaDecisionPublisher(kafkaTemplate, objectMapper, props.decisionTopic());
    }

    @Bean
    JourneyOrchestrator journeyOrchestrator(JourneyEngine engine, JourneyRegistry registry,
                                            JourneyInstanceStore store, CapabilityRequestPort capabilityRequestPort,
                                            DecisionOutboundPort decisionOutboundPort,
                                            Supplier<String> instanceIdSupplier) {
        return new JourneyOrchestrator(engine, registry, store, capabilityRequestPort, decisionOutboundPort,
                instanceIdSupplier);
    }
}

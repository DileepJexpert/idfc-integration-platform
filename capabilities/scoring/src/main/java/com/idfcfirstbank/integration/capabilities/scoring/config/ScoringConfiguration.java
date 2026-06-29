package com.idfcfirstbank.integration.capabilities.scoring.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.FicoHttpAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.MockFicoAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.scoring.application.ScoringService;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;
import com.idfcfirstbank.integration.capabilities.scoring.domain.service.DecisionRule;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Wires the framework-free service to its ports. The FICO adapter is chosen by
 * config ({@code idfc.scoring.fico-mode}); the concrete adapter is exposed only as
 * {@link FicoPort}. The pure {@link DecisionRule} and the {@link ScoringService}
 * (with the configured threshold) are plain beans.
 */
@Configuration
@EnableConfigurationProperties(ScoringProperties.class)
public class ScoringConfiguration {

    @Bean
    FicoPort ficoPort(ScoringProperties props) {
        return props.isReal() ? new FicoHttpAdapter(props.ficoUrl()) : new MockFicoAdapter();
    }

    @Bean
    DecisionRule decisionRule() {
        return new DecisionRule();
    }

    @Bean
    ScoringService scoringService(FicoPort ficoPort, DecisionRule decisionRule, ScoringProperties props) {
        return new ScoringService(ficoPort, decisionRule, props.threshold());
    }

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

    @Bean
    CapabilityResponsePort capabilityResponsePort(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        return new KafkaCapabilityResponsePublisher(kafkaTemplate, objectMapper);
    }
}

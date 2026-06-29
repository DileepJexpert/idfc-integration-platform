package com.idfcfirstbank.integration.capabilities.bureau.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.CibilHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilPort;
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
 * Wires the framework-free service to its ports. The CIBIL adapter is chosen by
 * config ({@code idfc.bureau.cibil.mode}); the concrete adapter is exposed only
 * as {@link CibilPort}.
 */
@Configuration
@EnableConfigurationProperties(CibilProperties.class)
public class BureauConfiguration {

    @Bean
    CibilPort cibilPort(CibilProperties props) {
        return props.isReal() ? new CibilHttpAdapter(props.url()) : new MockCibilAdapter();
    }

    @Bean
    BureauService bureauService(CibilPort cibilPort) {
        return new BureauService(cibilPort);
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

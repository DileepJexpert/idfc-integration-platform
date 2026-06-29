package com.idfcfirstbank.integration.capabilities.customer.party.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.MockPosidexAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.PosidexHttpAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.application.CustomerPartyService;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
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
 * Wires the framework-free service to its ports. The Posidex adapter is chosen by
 * config ({@code idfc.customer-party.posidex.mode}); the concrete adapter is
 * exposed only as {@link PosidexPort}.
 */
@Configuration
@EnableConfigurationProperties(PosidexProperties.class)
public class CustomerPartyConfiguration {

    @Bean
    PosidexPort posidexPort(PosidexProperties props) {
        return props.isReal() ? new PosidexHttpAdapter(props.url()) : new MockPosidexAdapter();
    }

    @Bean
    CustomerPartyService customerPartyService(PosidexPort posidexPort) {
        return new CustomerPartyService(posidexPort);
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

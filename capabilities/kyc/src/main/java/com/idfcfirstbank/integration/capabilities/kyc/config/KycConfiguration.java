package com.idfcfirstbank.integration.capabilities.kyc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.MockNsdlAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.NsdlHttpAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.application.KycService;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;
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
 * Wires the framework-free service to its ports. The NSDL adapter is chosen by
 * config ({@code idfc.kyc.nsdl.mode}); the concrete adapter is exposed only as
 * {@link NsdlPort}.
 */
@Configuration
@EnableConfigurationProperties(NsdlProperties.class)
public class KycConfiguration {

    @Bean
    NsdlPort nsdlPort(NsdlProperties props) {
        return props.isReal() ? new NsdlHttpAdapter(props.url()) : new MockNsdlAdapter();
    }

    @Bean
    KycService kycService(NsdlPort nsdlPort) {
        return new KycService(nsdlPort);
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

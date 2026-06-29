package com.idfcfirstbank.integration.capabilities.bureau.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.CibilHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial.CommercialBureauHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial.MockCommercialBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau.MockMultiBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau.MultiBureauHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.scorecard.MockScorecardInfraAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauFetchService;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.BureauVendorPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CapabilityResponsePort;
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
import java.util.List;
import java.util.Map;

/**
 * Wires the framework-free fetch/handler to the per-vendor ports. Each vendor
 * adapter is chosen by config (mock vs real HTTP) and exposed only as a
 * {@link BureauVendorPort}; the {@link BureauFetchService} fans out over all of
 * them.
 */
@Configuration
@EnableConfigurationProperties(BureauProperties.class)
public class BureauConfiguration {

    @Bean
    BureauVendorPort cibilPort(BureauProperties props) {
        return props.cibil().isReal() ? new CibilHttpAdapter(props.cibil().url()) : new MockCibilAdapter();
    }

    @Bean
    BureauVendorPort multiBureauPort(BureauProperties props) {
        return props.multiBureau().isReal()
                ? new MultiBureauHttpAdapter(props.multiBureau().url()) : new MockMultiBureauAdapter();
    }

    @Bean
    BureauVendorPort commercialBureauPort(BureauProperties props) {
        return props.commercial().isReal()
                ? new CommercialBureauHttpAdapter(props.commercial().url()) : new MockCommercialBureauAdapter();
    }

    @Bean
    BureauVendorPort scorecardInfraPort() {
        // Internal backing — mock only (no external vendor URL).
        return new MockScorecardInfraAdapter();
    }

    @Bean
    BureauFetchService bureauFetchService(List<BureauVendorPort> ports) {
        return new BureauFetchService(ports);
    }

    @Bean
    BureauService bureauService(BureauFetchService fetchService, BureauProperties props) {
        return new BureauService(fetchService, props.defaultBureauTypes());
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

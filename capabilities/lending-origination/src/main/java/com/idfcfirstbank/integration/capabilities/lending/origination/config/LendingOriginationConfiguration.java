package com.idfcfirstbank.integration.capabilities.lending.origination.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.FinnOneStoredProcAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.MockFinnOneAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.kafka.KafkaCapabilityResponsePublisher;
import com.idfcfirstbank.integration.capabilities.lending.origination.application.LendingOriginationService;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.CapabilityResponsePort;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * Wires the framework-free service to its ports. The FinnOne adapter is chosen by
 * config ({@code idfc.lending-origination.finnone.mode}); the concrete adapter is
 * exposed only as {@link FinnOneBookingPort}.
 *
 * <p>The real FinnOne adapter is JDBC against an Oracle stored proc and needs a
 * {@link DataSource}. We inject it via {@link ObjectProvider} and resolve it ONLY
 * in real mode, so the app starts in mock mode without any
 * {@code spring.datasource.*} configured. (DataSourceAutoConfiguration is excluded
 * on the application class; in real mode we build the DataSource here from
 * {@code spring.datasource.*}.)
 */
@Configuration
@EnableConfigurationProperties(FinnOneProperties.class)
public class LendingOriginationConfiguration {

    /**
     * Real-mode DataSource, built from {@code spring.datasource.*}. Defined as a
     * bean so it is created lazily (only injected through the ObjectProvider when
     * finnone.mode=real); in mock mode it is simply never resolved.
     */
    @Bean
    @Lazy
    DataSource finnOneDataSource(
            @Value("${spring.datasource.url:}") String url,
            @Value("${spring.datasource.username:}") String username,
            @Value("${spring.datasource.password:}") String password,
            @Value("${spring.datasource.driver-class-name:oracle.jdbc.OracleDriver}") String driverClassName) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName(driverClassName)
                .build();
    }

    @Bean
    FinnOneBookingPort finnOneBookingPort(FinnOneProperties props, ObjectProvider<DataSource> dataSource) {
        if (props.isReal()) {
            return new FinnOneStoredProcAdapter(dataSource.getObject());
        }
        return new MockFinnOneAdapter();
    }

    @Bean
    LendingOriginationService lendingOriginationService(FinnOneBookingPort finnOneBookingPort) {
        return new LendingOriginationService(finnOneBookingPort);
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

package com.idfcfirstbank.integration.capabilities.lending.origination.config;

import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.FinnOneStoredProcAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone.MockFinnOneAdapter;
import com.idfcfirstbank.integration.capabilities.lending.origination.application.LendingOriginationService;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.sql.DataSource;

/**
 * Wires the framework-free service to its ports. The FinnOne adapter is chosen by
 * config ({@code idfc.lending-origination.finnone.mode}); the concrete adapter is
 * exposed only as {@link FinnOneBookingPort}.
 *
 * <p>The Kafka shell is NOT wired here: the shared capability framework
 * (shared-capability auto-configuration, triggered by the
 * {@code LendingOriginationCapability} bean) consumes
 * {@code cap.lending-origination.request.v1}, dispatches idempotently — a
 * redelivered booking request never re-executes the FinnOne stored proc — and
 * publishes the confirmed response.
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
    com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.BrandValidationPort brandValidationPort() {
        // Mocked, config-as-data (brand-config/{brand}.json). Real brand API + Kong is a later step.
        return new com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.brand.MockBrandValidationAdapter();
    }

    @Bean
    LendingOriginationService lendingOriginationService(FinnOneBookingPort finnOneBookingPort,
            com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.BrandValidationPort brandValidationPort) {
        return new LendingOriginationService(finnOneBookingPort, brandValidationPort);
    }
}

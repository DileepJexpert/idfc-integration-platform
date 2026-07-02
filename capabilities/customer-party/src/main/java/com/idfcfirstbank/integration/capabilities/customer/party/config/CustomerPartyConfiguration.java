package com.idfcfirstbank.integration.capabilities.customer.party.config;

import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.MockPosidexAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex.PosidexHttpAdapter;
import com.idfcfirstbank.integration.capabilities.customer.party.application.CustomerPartyService;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free service to its ports. The Posidex adapter is chosen by
 * config ({@code idfc.customer-party.posidex.mode}); the concrete adapter is
 * exposed only as {@link PosidexPort}.
 *
 * <p>The Kafka shell is NOT wired here: it now comes from the shared capability
 * framework, triggered by the {@code CustomerPartyCapability} bean.
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
}

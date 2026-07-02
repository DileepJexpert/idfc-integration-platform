package com.idfcfirstbank.integration.capabilities.kyc.config;

import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.MockNsdlAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl.NsdlHttpAdapter;
import com.idfcfirstbank.integration.capabilities.kyc.application.KycService;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free service to its ports. The NSDL adapter is chosen by
 * config ({@code idfc.kyc.nsdl.mode}); the concrete adapter is exposed only as
 * {@link NsdlPort}.
 *
 * <p>The Kafka shell is NOT wired here: it now comes from the shared capability
 * framework, triggered by the {@code KycCapability} bean.
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
}

package com.idfcfirstbank.integration.capabilities.bureau.config;

import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.CibilHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.cibil.MockCibilAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial.CommercialBureauHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.commercial.MockCommercialBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau.MockMultiBureauAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.multibureau.MultiBureauHttpAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.adapter.out.scorecard.MockScorecardInfraAdapter;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauFetchService;
import com.idfcfirstbank.integration.capabilities.bureau.application.BureauService;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.BureauVendorPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the framework-free fetch/handler to the per-vendor ports. Each vendor
 * adapter is chosen by config (mock vs real HTTP) and exposed only as a
 * {@link BureauVendorPort}; the {@link BureauFetchService} fans out over all of
 * them.
 *
 * <p>The Kafka shell is NOT wired here: it now comes from the shared capability
 * framework, triggered by the {@code BureauCapability} bean.
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
}

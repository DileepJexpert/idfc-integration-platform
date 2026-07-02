package com.idfcfirstbank.integration.capabilities.scoring.config;

import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.FicoHttpAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico.MockFicoAdapter;
import com.idfcfirstbank.integration.capabilities.scoring.application.ScoringService;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;
import com.idfcfirstbank.integration.capabilities.scoring.domain.service.DecisionRule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free service to its ports. The FICO adapter is chosen by
 * config ({@code idfc.scoring.fico-mode}); the concrete adapter is exposed only as
 * {@link FicoPort}. The pure {@link DecisionRule} and the {@link ScoringService}
 * (with the configured threshold) are plain beans.
 *
 * <p>The Kafka shell is NOT wired here: it now comes from the shared capability
 * framework, triggered by the {@code ScoringCapability} bean.
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
}

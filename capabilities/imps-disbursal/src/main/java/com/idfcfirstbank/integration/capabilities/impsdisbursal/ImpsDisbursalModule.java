package com.idfcfirstbank.integration.capabilities.impsdisbursal;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.config.ImpsDisbursalProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the imps-disbursal capability beans — the {@code SyncInvocable}
 * service, the real HTTP {@link com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsFtPort}
 * client, and the idempotency store — into whatever sync-ingress app {@code @Import}s
 * it. The capability is a LIBRARY invoked in-thread by the edge, not a standalone
 * deployable, so it ships this module instead of a Spring Boot application.
 */
@Configuration
@ComponentScan
@EnableConfigurationProperties(ImpsDisbursalProperties.class)
public class ImpsDisbursalModule {
}

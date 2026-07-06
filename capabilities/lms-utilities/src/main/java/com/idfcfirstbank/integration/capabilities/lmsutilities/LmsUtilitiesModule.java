package com.idfcfirstbank.integration.capabilities.lmsutilities;

import com.idfcfirstbank.integration.capabilities.lmsutilities.config.LmsUtilitiesProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the lms-utilities capability beans — the {@code SyncInvocable} service
 * and the real HTTP {@link com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out.LmsUtilityPort}
 * client — into whatever sync-ingress app {@code @Import}s it. The capability is a
 * LIBRARY invoked in-thread by the edge, not a standalone deployable, so it ships
 * this module instead of a Spring Boot application.
 */
@Configuration
@ComponentScan
@EnableConfigurationProperties(LmsUtilitiesProperties.class)
public class LmsUtilitiesModule {
}

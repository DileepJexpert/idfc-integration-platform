package com.idfcfirstbank.integration.capabilities.sfdcusermgmt;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the sfdc-user-management capability beans — the {@code SyncInvocable}
 * service, the per-svcName route resolver composed with the ORG endpoint table, the
 * mapper registry (passthrough default), and the generic SFDC HTTP adapter — into
 * whatever sync-ingress app {@code @Import}s it. The capability is a LIBRARY invoked
 * in-thread by the digital edge (like imps-disbursal / lms-utilities), not a
 * standalone deployable, so it ships this module instead of a Spring Boot application.
 */
@Configuration
@ComponentScan
@EnableConfigurationProperties(SfdcUserManagementProperties.class)
public class SfdcUserManagementModule {
}

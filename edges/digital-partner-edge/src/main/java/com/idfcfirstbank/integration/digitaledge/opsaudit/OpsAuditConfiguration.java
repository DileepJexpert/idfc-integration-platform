package com.idfcfirstbank.integration.digitaledge.opsaudit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the edge's audited {@code /ops} surface: binds {@link OpsAuditProperties} and
 * registers the fail-closed {@link OpsAuditAuthFilter} scoped to {@code /ops/*}. The
 * store, query service, recorder adapter and controller are component-scanned.
 */
@Configuration
@EnableConfigurationProperties(OpsAuditProperties.class)
public class OpsAuditConfiguration {

    @Bean
    public FilterRegistrationBean<OpsAuditAuthFilter> opsAuditAuthFilter(OpsAuditProperties props) {
        FilterRegistrationBean<OpsAuditAuthFilter> reg =
                new FilterRegistrationBean<>(new OpsAuditAuthFilter(props.authToken()));
        reg.addUrlPatterns("/ops/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        reg.setName("opsAuditAuthFilter");
        return reg;
    }
}

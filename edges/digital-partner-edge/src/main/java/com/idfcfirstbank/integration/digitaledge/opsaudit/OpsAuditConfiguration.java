package com.idfcfirstbank.integration.digitaledge.opsaudit;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    /**
     * The sync-invocations view is a browser app — GET-only CORS on {@code /ops/**},
     * the same shape the engine's ops API uses (OpsQueryAutoConfiguration). Without
     * this the browser blocks the cross-origin read even though the server answers and
     * curl succeeds. The preflight is let through the auth filter (see
     * {@link OpsAuditAuthFilter#shouldNotFilter}); the real GET still needs the token.
     */
    @Bean
    public WebMvcConfigurer opsAuditCorsConfigurer(OpsAuditProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/ops/**")
                        .allowedOriginPatterns(props.corsAllowedOriginPatterns().toArray(String[]::new))
                        .allowedMethods("GET", "OPTIONS")
                        .allowedHeaders("Content-Type",
                                OpsAuditAuthFilter.TOKEN_HEADER, OpsAuditAuthFilter.ACTOR_HEADER);
            }
        };
    }
}

package com.idfcfirstbank.integration.platform.opsquery.config;

import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunQueryService;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunStore;
import com.idfcfirstbank.integration.platform.opsquery.web.OpsRunController;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Activates the ops read window in any app that provides an {@link OpsRunStore}
 * (the engine adapts its instance store). Everything the spec demands of the
 * transport lives here: the SEPARATE fail-closed ops token, the required actor
 * header, the per-request AUDIT line (ids only), CORS for the browser ops view,
 * and the {@code journeys.stuck.count} gauge.
 */
@AutoConfiguration
@ConditionalOnBean(OpsRunStore.class)
@EnableConfigurationProperties(OpsQueryProperties.class)
public class OpsQueryAutoConfiguration {

    public static final String TOKEN_HEADER = "X-Ops-Token";
    public static final String ACTOR_HEADER = "X-User-Id";

    private static final Logger audit = LoggerFactory.getLogger("ops.audit");

    @Bean
    OpsRunQueryService opsRunQueryService(
            OpsRunStore store,
            // The SAME liveness numbers the sweeper runs on, so "stuck" means
            // "approaching the budget the sweeper will enforce" (D9).
            @Value("${idfc.engine.liveness.run-budget-seconds:900}") long runBudgetSeconds,
            @Value("${idfc.engine.liveness.sweep-interval-ms:60000}") long sweepIntervalMs) {
        return new OpsRunQueryService(store, Clock.systemUTC(),
                Duration.ofSeconds(runBudgetSeconds), Duration.ofMillis(sweepIntervalMs));
    }

    @Bean
    OpsRunController opsRunController(OpsRunQueryService service) {
        return new OpsRunController(service);
    }

    /**
     * The only door (B.3 rules): every /ops call needs the ops token AND an
     * actor identity, and EVERY call — allowed or refused — leaves an audit
     * line with the actor. OPTIONS passes for CORS preflight (no custom
     * headers by spec). The ops token is a DIFFERENT secret from the
     * registry's (D14).
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> opsAuthFilter(OpsQueryProperties props) {
        String expected = props.authToken();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return HttpMethod.OPTIONS.matches(request.getMethod());
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String actor = request.getHeader(ACTOR_HEADER);
                boolean tokenOk = expected.equals(request.getHeader(TOKEN_HEADER));
                boolean actorOk = actor != null && !actor.isBlank();
                // The audit trail records every read attempt, refused ones included.
                audit.info("ops.audit actor={} allowed={} method={} path={} query={}",
                        actorOk ? actor : "-", tokenOk && actorOk, request.getMethod(),
                        request.getRequestURI(), request.getQueryString());
                if (!tokenOk) {
                    reject(response, "invalid or missing " + TOKEN_HEADER);
                    return;
                }
                if (!actorOk) {
                    reject(response, ACTOR_HEADER + " header is required for the ops API");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/ops/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    private static void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"UNAUTHENTICATED\",\"message\":\"" + message + "\"}");
    }

    /** The ops view is a browser app (Phase 1) — GET-only CORS on /ops/**. */
    @Bean
    WebMvcConfigurer opsCors(OpsQueryProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/ops/**")
                        .allowedOriginPatterns(props.corsAllowedOriginPatterns().toArray(String[]::new))
                        .allowedMethods("GET", "OPTIONS")
                        .allowedHeaders("Content-Type", TOKEN_HEADER, ACTOR_HEADER);
            }
        };
    }

    /**
     * {@code journeys.stuck.count} for dashboards/alerts. The value is cached
     * ~15s so a metrics scrape never triggers a store scan storm.
     */
    @Bean
    Object journeysStuckGauge(ObjectProvider<MeterRegistry> registries, OpsRunQueryService service) {
        MeterRegistry registry = registries.getIfAvailable();
        if (registry == null) {
            return new Object(); // no metrics backend in this context (tests)
        }
        AtomicLong cached = new AtomicLong();
        AtomicLong lastComputedMs = new AtomicLong();
        Gauge.builder("journeys.stuck.count", () -> {
            long now = System.currentTimeMillis();
            if (now - lastComputedMs.get() > 15_000) {
                lastComputedMs.set(now);
                cached.set(service.stuckCount());
            }
            return cached.get();
        }).description("RUNNING journeys approaching/past the run budget (budget - sweep interval)")
                .register(registry);
        return cached;
    }
}

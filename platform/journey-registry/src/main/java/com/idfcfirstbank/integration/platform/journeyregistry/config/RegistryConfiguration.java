package com.idfcfirstbank.integration.platform.journeyregistry.config;

import com.aerospike.client.AerospikeClient;
import com.aerospike.client.policy.ClientPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store.AerospikeJourneyRegistryStore;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store.InMemoryJourneyRegistryStore;
import com.idfcfirstbank.integration.platform.journeyregistry.application.JourneyConfigValidator;
import com.idfcfirstbank.integration.platform.journeyregistry.application.RegistryService;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.port.JourneyRegistryStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.time.Clock;

/**
 * Wires the framework-free registry core to Spring. Two transport-level guards
 * live here, NOT in the service: the service token (every /api call) and CORS
 * (the DAG Designer is a browser app). Actor identity (X-User-Id) is a SERVICE
 * rule — the service 401s without it — because maker-checker is business law,
 * not transport configuration.
 */
@Configuration
@EnableConfigurationProperties(RegistryProperties.class)
public class RegistryConfiguration {

    public static final String TOKEN_HEADER = "X-Registry-Token";
    public static final String ACTOR_HEADER = "X-User-Id";

    @Bean
    Clock registryClock() {
        return Clock.systemUTC();
    }

    @Bean
    JourneyConfigValidator journeyConfigValidator() {
        return new JourneyConfigValidator();
    }

    /**
     * Durable Aerospike when {@code idfc.registry.store=aerospike}; in-memory
     * (Docker-free) default. Exposed only as the port — a swap, not a rewrite.
     */
    @Bean
    JourneyRegistryStore journeyRegistryStore(RegistryProperties props) {
        if (props.usesAerospike()) {
            var aero = props.aerospike();
            var policy = new ClientPolicy();
            policy.failIfNotConnected = false;
            policy.timeout = 3000;
            var client = new AerospikeClient(policy, aero.host(), aero.port());
            return new AerospikeJourneyRegistryStore(client, aero.namespace(),
                    aero.metaSet(), aero.versionSet());
        }
        return new InMemoryJourneyRegistryStore();
    }

    @Bean
    RegistryService registryService(JourneyRegistryStore store, JourneyConfigValidator validator,
                                    ObjectMapper objectMapper, Clock registryClock) {
        return new RegistryService(store, validator, objectMapper, registryClock);
    }

    /**
     * Service-token gate on every /api call (mock single-token auth, same tier as
     * the edges; real Hydra+Kong is a later slice — the token is ALREADY fail-closed
     * at startup via {@link RegistryProperties}). OPTIONS passes through: CORS
     * preflights carry no custom headers by spec, and MVC answers them before any
     * handler runs.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> registryTokenFilter(RegistryProperties props) {
        String expected = props.authToken();
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return HttpMethod.OPTIONS.matches(request.getMethod());
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if (!expected.equals(request.getHeader(TOKEN_HEADER))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            "{\"error\":\"UNAUTHENTICATED\",\"message\":\"invalid or missing "
                                    + TOKEN_HEADER + "\",\"issues\":[]}");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** The DAG Designer runs in a browser (Flutter web) — its dev origins must preflight-pass. */
    @Bean
    WebMvcConfigurer registryCors(RegistryProperties props) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(props.corsAllowedOriginPatterns().toArray(String[]::new))
                        .allowedMethods("GET", "POST", "PUT", "OPTIONS")
                        .allowedHeaders("Content-Type", TOKEN_HEADER, ACTOR_HEADER);
            }
        };
    }
}

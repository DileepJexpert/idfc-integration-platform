package com.idfcfirstbank.integration.platform.journeyregistry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.application.RegistryService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * LOCAL-ONLY: self-seed the bundled canonical journeys at startup so the DAG
 * Designer (real registry, no mock) and the {@code --registry} engine seam both
 * have journeys the moment the service is up. Gated to the {@code local} profile
 * — production journeys are authored by humans in the Designer, never seeded.
 *
 * <p>An {@link ApplicationRunner} (not a blocking {@code @PostConstruct}) so the
 * context finishes refreshing first; the run-services launcher's registry seam
 * waits on {@code /published-journeys} becoming non-empty before it starts the
 * engine, closing the brief window between "port open" and "seed complete".
 */
@Configuration
@Profile("local")
public class LocalSeedConfiguration {

    @Bean
    ApplicationRunner registrySeeder(RegistryService service, ObjectMapper objectMapper) {
        RegistrySeeder seeder = new RegistrySeeder(service, objectMapper);
        return args -> seeder.seedFromClasspath(RegistrySeeder.DEFAULT_LOCATION);
    }
}

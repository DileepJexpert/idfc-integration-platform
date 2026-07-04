package com.idfcfirstbank.integration.capabilities.devicefinancing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * DEMO SCAFFOLDING — the brand-as-config capability app (legacy-patterns demo).
 *
 * <p>In the legacy estate this behaviour was a dedicated Spring Boot service
 * per concern with 26 per-brand config files and a splitter in front. Here the
 * SAME behaviour is: one journey (device-financing.journey.json) + the config
 * rows in this app's application.yml. Adding a brand is a config row — see
 * {@code demo/README.md}. Vendors are mocked in-process; nothing in this module
 * is production code, and the census-gated generic-http capability
 * (docs/legacy-analysis-review.md §6) must not be grown out of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DeviceFinancingApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceFinancingApplication.class, args);
    }
}

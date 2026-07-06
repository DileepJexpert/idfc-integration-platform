package com.idfcfirstbank.integration.capabilities.devicevalidation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The device-validation capability app: brand-as-config device VALIDATION
 * (validate / block / unblock) on the platform's capability framework.
 *
 * <p>In the legacy estate this behaviour was a dedicated Spring Boot service
 * per concern with a per-brand config file each and a splitter in front. Here
 * the SAME behaviour is: one journey (device-validation.journey.json) + the
 * config rows in this app's application.yml. Adding a brand is a config row. The
 * vendor call is REAL HTTP with real per-brand auth; only the vendor's response
 * DATA is mocked in dev (the mock-vendors WireMock). The census-gated
 * generic-http capability (docs/legacy-analysis-review.md §6) must not be grown
 * out of it.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DeviceValidationApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceValidationApplication.class, args);
    }
}

package com.idfcfirstbank.integration.platform.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code platform-config} capability is implemented in a later slice.
 */
@SpringBootApplication
public class PlatformConfigApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformConfigApplication.class, args);
    }
}

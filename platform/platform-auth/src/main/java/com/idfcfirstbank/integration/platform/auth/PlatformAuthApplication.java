package com.idfcfirstbank.integration.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code platform-auth} capability is implemented in a later slice.
 */
@SpringBootApplication
public class PlatformAuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformAuthApplication.class, args);
    }
}

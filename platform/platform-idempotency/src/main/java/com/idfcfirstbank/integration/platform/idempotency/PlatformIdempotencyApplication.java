package com.idfcfirstbank.integration.platform.idempotency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code platform-idempotency} capability is implemented in a later slice.
 */
@SpringBootApplication
public class PlatformIdempotencyApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformIdempotencyApplication.class, args);
    }
}

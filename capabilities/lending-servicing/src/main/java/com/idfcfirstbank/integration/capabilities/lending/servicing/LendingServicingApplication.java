package com.idfcfirstbank.integration.capabilities.lending.servicing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code lending-servicing} capability is implemented in a later slice.
 */
@SpringBootApplication
public class LendingServicingApplication {
    public static void main(String[] args) {
        SpringApplication.run(LendingServicingApplication.class, args);
    }
}

package com.idfcfirstbank.integration.capabilities.customer.party;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code customer-party} capability is implemented in a later slice.
 */
@SpringBootApplication
public class CustomerPartyApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerPartyApplication.class, args);
    }
}

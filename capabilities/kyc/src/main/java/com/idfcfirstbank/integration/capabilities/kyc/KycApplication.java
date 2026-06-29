package com.idfcfirstbank.integration.capabilities.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code kyc} capability is implemented in a later slice.
 */
@SpringBootApplication
public class KycApplication {
    public static void main(String[] args) {
        SpringApplication.run(KycApplication.class, args);
    }
}

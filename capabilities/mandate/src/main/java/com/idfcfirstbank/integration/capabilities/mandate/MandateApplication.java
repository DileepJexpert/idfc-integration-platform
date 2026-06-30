package com.idfcfirstbank.integration.capabilities.mandate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** mandate capability app. Declares the MandateCapability bean; the shared
 * framework wires the Kafka shell + idempotent dispatch around it. */
@SpringBootApplication
public class MandateApplication {
    public static void main(String[] args) {
        SpringApplication.run(MandateApplication.class, args);
    }
}

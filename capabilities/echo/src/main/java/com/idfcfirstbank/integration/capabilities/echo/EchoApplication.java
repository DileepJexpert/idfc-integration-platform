package com.idfcfirstbank.integration.capabilities.echo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** echo capability app. Declares one Capability bean; the shared framework
 * (shared-capability auto-configuration) wires the Kafka shell + idempotent
 * dispatch around it. */
@SpringBootApplication
public class EchoApplication {
    public static void main(String[] args) {
        SpringApplication.run(EchoApplication.class, args);
    }
}

package com.idfcfirstbank.integration.edges.sfdcegress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Slice 1 STUB. Starts and serves /actuator/health; no business logic yet.
 * The real {@code sfdc-egress-edge} capability is implemented in a later slice.
 */
@SpringBootApplication
public class SfdcEgressEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SfdcEgressEdgeApplication.class, args);
    }
}

package in.idfc.integration.edges.sfdcingress;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SFDC ingress edge — Slice 1. A thin protocol edge: authenticate, validate,
 * dedupe (idempotency-first), normalize to a canonical envelope, route, fast-ACK.
 * No business logic lives here.
 */
@SpringBootApplication
public class SfdcIngressEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(SfdcIngressEdgeApplication.class, args);
    }
}

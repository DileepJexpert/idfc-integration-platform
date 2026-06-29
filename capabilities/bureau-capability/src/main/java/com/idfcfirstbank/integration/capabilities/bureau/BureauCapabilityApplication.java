package com.idfcfirstbank.integration.capabilities.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bureau capability — Slice 2. ONE capability that fetches and normalizes
 * credit-bureau data (CIBIL / Multi-Bureau / Commercial / Bureau-Score),
 * replacing the 4-5x duplicated bureau-fetch code across the credit services.
 *
 * <p>Stateless DATA capability: it fetches + normalizes + returns. It does NOT
 * score, decide eligibility, or do KYC. Vendors are adapters behind OUT ports;
 * the capability is invoked (REST in Slice 2), never calls another capability.
 */
@SpringBootApplication
public class BureauCapabilityApplication {
    public static void main(String[] args) {
        SpringApplication.run(BureauCapabilityApplication.class, args);
    }
}

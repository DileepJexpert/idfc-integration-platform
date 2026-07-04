package com.idfcfirstbank.integration.capabilities.fusionhcm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * fusion-hcm capability — the per-record Fusion HCM update ({@code updateEmployee})
 * and sync read ({@code getEmployee}), invoked by the engine over the capability
 * framework like any other capability. The vendor call is real HTTP with real
 * timeouts and HTTP-status → failure-class mapping; in dev the vendor is the
 * compose WireMock (only its response DATA is mocked).
 *
 * <p>The file-batch INGRESS that feeds this capability one run per CSV record is
 * a separate deployable ({@code edges:file-batch-edge}) — an edge, not a
 * capability. The production SFTP edge and in-journey {@code foreach} execution
 * are census-gated (docs/legacy-analysis-review.md §6/§8).
 */
@SpringBootApplication
public class FusionHcmApplication {
    public static void main(String[] args) {
        SpringApplication.run(FusionHcmApplication.class, args);
    }
}

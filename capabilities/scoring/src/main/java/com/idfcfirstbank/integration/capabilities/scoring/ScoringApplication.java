package com.idfcfirstbank.integration.capabilities.scoring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * scoring capability app — the DECISIONING capability. Consumes
 * {@code cap.scoring.request.v1}, reads the upstream bureau result from
 * {@code collectedResults}, enriches via FICO (mock/real adapter), applies the
 * pure decision rule, and replies on {@code cap.scoring.response.v1} with a
 * {@code decision} of {@code APPROVED}/{@code REJECTED} per THE CAPABILITY CONTRACT.
 */
@SpringBootApplication
public class ScoringApplication {
    public static void main(String[] args) {
        SpringApplication.run(ScoringApplication.class, args);
    }
}

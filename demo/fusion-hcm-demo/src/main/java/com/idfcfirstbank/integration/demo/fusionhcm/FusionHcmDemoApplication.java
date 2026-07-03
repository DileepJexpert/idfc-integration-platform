package com.idfcfirstbank.integration.demo.fusionhcm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DEMO SCAFFOLDING — the file-batch pattern app (legacy-patterns demo).
 *
 * <p>Legacy shape (hrapps LWD): a scheduler pulls an employee CSV off SFTP,
 * loops records against Fusion, emails a result file — fire-and-forget, no
 * idempotency on re-run. Demo shape here: a LOCAL-FOLDER poller (explicitly
 * NOT SFTP) parses the CSV and starts ONE ENGINE RUN PER RECORD, so per-record
 * status, retry classes, DLQ and the ops view all ride the existing platform —
 * and re-processing is refused twice over (content ledger + engine dedup).
 * The production SFTP edge and in-journey {@code foreach} execution are
 * census-gated; this module must never grow into them.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FusionHcmDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(FusionHcmDemoApplication.class, args);
    }
}

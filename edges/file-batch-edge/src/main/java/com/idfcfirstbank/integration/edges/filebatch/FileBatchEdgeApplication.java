package com.idfcfirstbank.integration.edges.filebatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * file-batch ingress edge — a LOCAL-FOLDER CSV poller (explicitly NOT SFTP) that
 * parses each dropped file and starts ONE ENGINE RUN PER RECORD, so per-record
 * status, retry classes, DLQ and the ops view all ride the existing platform,
 * and re-processing is refused twice over (content ledger + engine dedup).
 *
 * <p>Disabled by default ({@code file-batch.enabled=false}); enable per run.
 * The production SFTP edge and in-journey {@code foreach} execution are
 * census-gated (docs/legacy-analysis-review.md §6/§8) — this is the pre-SFTP,
 * local-folder shape and must never grow into them.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FileBatchEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FileBatchEdgeApplication.class, args);
    }
}

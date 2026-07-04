package com.idfcfirstbank.integration.edges.filebatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * THE DEMO FILE EDGE — a local-folder CSV poller (explicitly NOT the
 * production SFTP edge; that is census-gated). For each new batch file it
 * starts ONE ENGINE RUN PER RECORD by publishing a canonical envelope per row:
 *
 * <ul>
 *   <li>{@code correlationId = batchId + "-r" + row} — DETERMINISTIC and
 *       per-record: it is the engine's run-dedup key, so even with the ledger
 *       wiped, insertIfAbsent refuses to double-run a record (the legacy LWD
 *       job re-runs blind);</li>
 *   <li>{@code notificationId = batchId} — shared by the whole batch: ONE
 *       exact ops-search key shows "batch of 5: 4 succeeded, 1 failed";</li>
 *   <li>an EMPTY file is logged and skipped (the production shape emails an
 *       alert — that adapter is part of the census-gated build);</li>
 *   <li>a structurally malformed file is quarantined loudly, never half-run.</li>
 * </ul>
 *
 * Payloads carry employee IDS and dates only — no names, no PII.
 */
@Component
public class FolderBatchPoller {

    private static final Logger log = LoggerFactory.getLogger(FolderBatchPoller.class);

    private final BatchInboxProperties props;
    private final EnvelopePublisher publisher;
    private final ObjectMapper objectMapper;

    public FolderBatchPoller(BatchInboxProperties props, EnvelopePublisher publisher,
                             ObjectMapper objectMapper) {
        this.props = props;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${file-batch.poll-ms:2000}")
    public void scan() {
        if (!props.enabled()) {
            return;
        }
        Path inbox = Path.of(props.inboxDir());
        if (!Files.isDirectory(inbox)) {
            return;
        }
        ProcessedLedger ledger = new ProcessedLedger(inbox.resolve(".processed.ledger"));
        try (Stream<Path> files = Files.list(inbox)) {
            files.filter(f -> f.getFileName().toString().endsWith(".csv"))
                    .sorted()
                    .forEach(f -> processFile(f, ledger));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list batch inbox", e);
        }
    }

    void processFile(Path file, ProcessedLedger ledger) {
        final byte[] content;
        try {
            content = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read batch file", e);
        }
        String hash = ProcessedLedger.hashOf(content);
        String batchId = "batch-" + hash.substring(0, 12);

        if (ledger.contains(hash)) {
            log.info("batch.skip.already-processed batchId={} file={}", batchId,
                    file.getFileName());
            archive(file, "processed");
            return;
        }

        final List<CsvBatchParser.BatchRecord> records;
        try {
            records = CsvBatchParser.parse(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        } catch (CsvBatchParser.MalformedBatchFileException e) {
            // Structural problem = the FILE is poison: quarantine it loudly and
            // ledger it so the poller never loops on it. Never half-run a batch.
            log.error("batch.quarantined batchId={} file={} reason={}", batchId,
                    file.getFileName(), e.getMessage());
            ledger.record(hash);
            archive(file, "quarantine");
            return;
        }

        if (records.isEmpty()) {
            log.warn("batch.empty batchId={} file={} — skipped (production shape "
                    + "emails an alert; that adapter is census-gated)", batchId,
                    file.getFileName());
            ledger.record(hash);
            archive(file, "processed");
            return;
        }

        for (int i = 0; i < records.size(); i++) {
            CsvBatchParser.BatchRecord record = records.get(i);
            String recordCorrelationId = batchId + "-r" + (i + 1);
            publisher.publish(props.originationTopic(), recordCorrelationId,
                    envelopeJson(batchId, recordCorrelationId, record));
        }
        ledger.record(hash);
        archive(file, "processed");
        log.info("batch.dispatched batchId={} records={} topic={} (one run per "
                + "record; ops search key = batchId)", batchId, records.size(),
                props.originationTopic());
    }

    private String envelopeJson(String batchId, String recordCorrelationId,
                                CsvBatchParser.BatchRecord record) {
        // Built as a map on purpose: the demo source marker "FILE_DEMO" is not a
        // SourceSystem enum value — the enum gains a FILE member only when the
        // census-gated production file edge exists.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("transactionId", recordCorrelationId + "-t");
        envelope.put("schemaVersion", "file-demo.v1");
        envelope.put("source", "FILE_DEMO");
        envelope.put("type", props.type());
        // The engine's run id derives from correlationId (its dedup key) — so
        // the PER-RECORD id rides there, and the shared batch id rides
        // notificationId, which the ops window matches exactly for grouping.
        envelope.put("notificationId", batchId);
        envelope.put("orgId", props.orgId());
        envelope.put("sfdcRecordId", record.employeeId());
        envelope.put("applicationRef", batchId + "/" + record.employeeId());
        envelope.put("correlationId", recordCorrelationId);
        envelope.put("originalCorrelationId", recordCorrelationId);
        envelope.put("payloadContentType", "application/json");
        envelope.put("occurredAt", Instant.now().toString());
        envelope.put("payload", Map.of(
                "employeeId", record.employeeId(),
                "lastWorkingDay", record.lastWorkingDay()));
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize demo envelope", e);
        }
    }

    private void archive(Path file, String subdir) {
        try {
            Path target = file.getParent().resolve(subdir);
            Files.createDirectories(target);
            Files.move(file, target.resolve(file.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot archive batch file", e);
        }
    }
}

package com.idfcfirstbank.integration.edges.filebatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The demo file edge's contract, unit-level: strict structure / permissive
 * values in the parser, the content-hash ledger (the re-run protection the
 * legacy LWD job lacks), one DETERMINISTIC envelope per record grouped by the
 * batch correlationId, the empty-file skip, and quarantine-not-half-run for
 * structurally bad files.
 */
class FileBatchScaffoldTest {

    private static final String SAMPLE = """
            employeeId,lastWorkingDay
            EMP-001,2026-07-31
            EMP-002,2026-08-15
            EMP-003,2026-07-20
            EMP-004,not-a-date
            EMP-005,2026-09-01
            """;

    // ---- parser ----------------------------------------------------------------

    @Test
    void parser_isStrictOnStructure_permissiveOnValues() {
        var records = CsvBatchParser.parse(SAMPLE);
        assertThat(records).hasSize(5);
        // The crafted bad DATE parses fine here — the CAPABILITY classifies it,
        // so exactly that record's run fails with a class (per-record status).
        assertThat(records.get(3).lastWorkingDay()).isEqualTo("not-a-date");

        assertThatThrownBy(() -> CsvBatchParser.parse("wrong,header\nEMP-1,2026-01-01"))
                .isInstanceOf(CsvBatchParser.MalformedBatchFileException.class);
        assertThatThrownBy(() -> CsvBatchParser.parse(
                "employeeId,lastWorkingDay\nEMP-1,2026-01-01,EXTRA"))
                .isInstanceOf(CsvBatchParser.MalformedBatchFileException.class);
        assertThat(CsvBatchParser.parse("")).isEmpty();
        assertThat(CsvBatchParser.parse("employeeId,lastWorkingDay")).isEmpty();
    }

    // ---- ledger ----------------------------------------------------------------

    @Test
    void ledger_dedupesByContentHash(@TempDir Path dir) {
        var ledger = new ProcessedLedger(dir.resolve(".processed.ledger"));
        String hash = ProcessedLedger.hashOf(SAMPLE.getBytes(StandardCharsets.UTF_8));
        assertThat(ledger.contains(hash)).isFalse();
        ledger.record(hash);
        assertThat(ledger.contains(hash)).isTrue();
        // Same content, different name/time -> same hash -> still deduped.
        assertThat(ledger.contains(
                ProcessedLedger.hashOf(SAMPLE.getBytes(StandardCharsets.UTF_8)))).isTrue();
    }

    // ---- poller ----------------------------------------------------------------

    private record Sent(String topic, String key, String json) {
    }

    private static final class RecordingPublisher implements EnvelopePublisher {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public void publish(String topic, String key, String envelopeJson) {
            sent.add(new Sent(topic, key, envelopeJson));
        }
    }

    private static FolderBatchPoller poller(RecordingPublisher publisher) {
        return new FolderBatchPoller(
                new BatchInboxProperties(true, "unused", 1000,
                        "orig.employee-lwd-update.v1", "EMPLOYEE_LWD_UPDATE", "HR-DEMO"),
                publisher, new ObjectMapper());
    }

    @Test
    void oneDeterministicEnvelopePerRecord_groupedByBatchNotificationId(
            @TempDir Path dir) throws Exception {
        Path file = dir.resolve("employees.csv");
        Files.writeString(file, SAMPLE);
        var publisher = new RecordingPublisher();
        var ledger = new ProcessedLedger(dir.resolve(".processed.ledger"));

        poller(publisher).processFile(file, ledger);

        assertThat(publisher.sent).hasSize(5);
        var mapper = new ObjectMapper();
        JsonNode first = mapper.readTree(publisher.sent.get(0).json());
        String batchId = first.get("notificationId").asText();
        assertThat(batchId).startsWith("batch-");
        for (int i = 0; i < 5; i++) {
            JsonNode env = mapper.readTree(publisher.sent.get(i).json());
            assertThat(env.get("notificationId").asText())
                    .as("every record rides the batch id — ONE exact ops search key")
                    .isEqualTo(batchId);
            assertThat(env.get("correlationId").asText())
                    .as("deterministic per-record id = the ENGINE's dedup key on re-drop")
                    .isEqualTo(batchId + "-r" + (i + 1));
            assertThat(env.get("type").asText()).isEqualTo("EMPLOYEE_LWD_UPDATE");
            assertThat(env.get("payload").get("employeeId").asText())
                    .isEqualTo("EMP-00" + (i + 1));
            assertThat(env.get("payload").has("name"))
                    .as("ids only — no PII fields ride the envelope")
                    .isFalse();
        }
        // File archived + ledgered: a second processFile of the SAME CONTENT is a no-op.
        Path redrop = dir.resolve("employees-again.csv");
        Files.writeString(redrop, SAMPLE);
        poller(publisher).processFile(redrop, ledger);
        assertThat(publisher.sent).hasSize(5);
        assertThat(Files.exists(dir.resolve("processed").resolve("employees.csv"))).isTrue();
    }

    @Test
    void emptyFile_isSkippedAndLedgered_neverDispatched(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("empty.csv");
        Files.writeString(file, "employeeId,lastWorkingDay\n");
        var publisher = new RecordingPublisher();
        var ledger = new ProcessedLedger(dir.resolve(".processed.ledger"));

        poller(publisher).processFile(file, ledger);

        assertThat(publisher.sent).isEmpty();
        assertThat(Files.exists(dir.resolve("processed").resolve("empty.csv"))).isTrue();
    }

    @Test
    void structurallyBadFile_isQuarantined_notHalfRun(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("bad.csv");
        Files.writeString(file, "employeeId,lastWorkingDay\nEMP-1,2026-01-01,EXTRA\n");
        var publisher = new RecordingPublisher();
        var ledger = new ProcessedLedger(dir.resolve(".processed.ledger"));

        poller(publisher).processFile(file, ledger);

        assertThat(publisher.sent)
                .as("a poison file must publish NOTHING — never a partial batch")
                .isEmpty();
        assertThat(Files.exists(dir.resolve("quarantine").resolve("bad.csv"))).isTrue();
    }
}

package com.idfcfirstbank.integration.demo.fusionhcm.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * DEMO CSV parser — ids only by design (PII rule: the sample carries employee
 * IDS and dates, never names). Structure is checked strictly (exact header,
 * two fields per row: parse fails loudly); VALUES are passed through untouched
 * so a malformed date reaches the capability and fails THAT record's run with
 * a class — which is the per-record-status demo.
 */
public final class CsvBatchParser {

    public static final String HEADER = "employeeId,lastWorkingDay";

    /** One CSV row — the per-record run's payload (ids + a date, nothing else). */
    public record BatchRecord(String employeeId, String lastWorkingDay) {
    }

    /** Thrown on structural problems (wrong header / field count): the FILE is bad. */
    public static final class MalformedBatchFileException extends RuntimeException {
        public MalformedBatchFileException(String message) {
            super(message);
        }
    }

    private CsvBatchParser() {
    }

    public static List<BatchRecord> parse(String content) {
        List<String> lines = content == null ? List.of() : content.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        if (!HEADER.equals(lines.get(0))) {
            throw new MalformedBatchFileException(
                    "first line must be exactly '" + HEADER + "'");
        }
        List<BatchRecord> records = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] fields = lines.get(i).split(",", -1);
            if (fields.length != 2) {
                throw new MalformedBatchFileException(
                        "row " + (i + 1) + " has " + fields.length + " fields, expected 2");
            }
            records.add(new BatchRecord(fields[0].trim(), fields[1].trim()));
        }
        return records;
    }
}

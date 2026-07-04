package com.idfcfirstbank.integration.edges.filebatch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * DEMO-GRADE idempotency for the file edge: a content-hash ledger so the same
 * batch file (by BYTES, any filename) is never re-processed. The legacy LWD
 * job has NO re-run protection; the demo has two layers — this ledger at the
 * file level, and the engine's insertIfAbsent dedup at the run level (the
 * per-record notificationIds are deterministic). Production would use the
 * platform idempotency store; a flat file is deliberately demo-obvious.
 */
public final class ProcessedLedger {

    private final Path ledgerFile;

    public ProcessedLedger(Path ledgerFile) {
        this.ledgerFile = ledgerFile;
    }

    public static String hashOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public boolean contains(String hash) {
        return load().contains(hash);
    }

    public void record(String hash) {
        try {
            Files.createDirectories(ledgerFile.getParent());
            Files.writeString(ledgerFile, hash + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to batch ledger", e);
        }
    }

    private Set<String> load() {
        if (!Files.exists(ledgerFile)) {
            return Set.of();
        }
        try {
            return new HashSet<>(Files.readAllLines(ledgerFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read batch ledger", e);
        }
    }
}

package com.idfcfirstbank.integration.platform.journeyregistry.domain.model;

import java.time.Instant;

/**
 * One immutable-once-terminal journey version: the §7 config artifact (stored
 * verbatim as JSON text — the registry never reinterprets a byte of it after the
 * server-owned {@code journeyKey}/{@code version} stamp) plus its maker-checker
 * audit fields.
 */
public record JourneyVersionRecord(
        String journeyKey,
        int version,
        VersionStatus status,
        String authorId,
        String approverId,
        String note,
        String configJson,
        Instant createdAt,
        Instant updatedAt) {

    public JourneyVersionRecord withConfig(String newConfigJson, String newNote, Instant now) {
        return new JourneyVersionRecord(journeyKey, version, status, authorId, approverId,
                newNote == null ? note : newNote, newConfigJson, createdAt, now);
    }

    public JourneyVersionRecord withStatus(VersionStatus newStatus, String newApproverId,
                                           String newNote, Instant now) {
        return new JourneyVersionRecord(journeyKey, version, newStatus, authorId,
                newApproverId == null ? approverId : newApproverId,
                newNote == null ? note : newNote, configJson, createdAt, now);
    }
}

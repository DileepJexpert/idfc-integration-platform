package com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.ValidationIssue;

import java.time.Instant;
import java.util.List;

/**
 * Wire shapes of the registry API — field names and status strings are the DAG
 * Designer's vocabulary (Journey/JourneyVersion/ValidationIssue models), so the
 * designer's HttpJourneyRepository maps 1:1. The §7 config travels as REAL JSON
 * (a node, not an escaped string): what Postman shows is what the engine loads.
 */
public final class RegistryDtos {

    private RegistryDtos() {
    }

    // ---- responses -----------------------------------------------------------

    /** One journey + its version list ({@code id} == {@code key} — the designer's Journey.id). */
    public record JourneyDto(
            String id,
            String key,
            String name,
            String businessLine,
            String product,
            String partner,
            Integer activeVersion,
            List<VersionDto> versions) {

        public static JourneyDto of(JourneyMeta meta, List<VersionDto> versions) {
            return new JourneyDto(meta.key(), meta.key(), meta.name(), meta.businessLine(),
                    meta.product(), meta.partner(),
                    meta.hasPublished() ? meta.publishedVersion() : null, versions);
        }
    }

    /** One version; {@code config} is included on single-version reads, null in lists. */
    public record VersionDto(
            String journeyKey,
            int version,
            String status,
            String authorId,
            String approverId,
            String note,
            Instant createdAt,
            Instant updatedAt,
            JsonNode config) {

        public static VersionDto of(JourneyVersionRecord r, ObjectMapper mapper, boolean withConfig) {
            return new VersionDto(r.journeyKey(), r.version(), r.status().wireName(),
                    r.authorId(), r.approverId(), r.note(), r.createdAt(), r.updatedAt(),
                    withConfig ? parse(mapper, r.configJson()) : null);
        }
    }

    /** Engine-facing read: the published §7 artifact plus its registry identity. */
    public record PublishedConfigDto(String journeyKey, int version, JsonNode config) {

        public static PublishedConfigDto of(JourneyVersionRecord r, ObjectMapper mapper) {
            return new PublishedConfigDto(r.journeyKey(), r.version(), parse(mapper, r.configJson()));
        }
    }

    /** Mirror of the designer's ValidationResult: just the issue list. */
    public record ValidationResultDto(List<ValidationIssue> issues) {
    }

    /** RegistryException on the wire; {@code issues} is non-empty for 422s. */
    public record ErrorBody(String error, String message, List<ValidationIssue> issues) {
    }

    // ---- requests ------------------------------------------------------------

    public record CreateJourneyRequest(String key, String name, String businessLine,
                                       String product, String partner) {
    }

    /** Draft create/save: the §7 config as real JSON + an optional audit note. */
    public record DraftRequest(JsonNode config, String note) {

        public String configJson() {
            return config == null ? "{}" : config.toString();
        }
    }

    public record RejectRequest(String comment) {
    }

    private static JsonNode parse(ObjectMapper mapper, String configJson) {
        try {
            return mapper.readTree(configJson);
        } catch (Exception e) {
            // Stored configs were parsed at stamp time — unreachable in practice.
            throw new IllegalStateException("stored config is not parseable JSON", e);
        }
    }
}

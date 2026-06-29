package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.util.Map;

/**
 * One bureau's report, NORMALIZED to the canonical shape. Each vendor adapter
 * translates its own wire shape into this — callers (and scoring downstream)
 * never see vendor-specific shapes. This is the anti-fragmentation contract: the
 * superset of what the absorbed services needed, in one place.
 *
 * @param type             which bureau produced this
 * @param score            normalized credit score
 * @param grade            normalized grade band (A/B/C/...)
 * @param reportId         the vendor's report reference
 * @param source           the vendor/system that served it (audit)
 * @param fetchedAt        ISO-8601 fetch time (audit / cache freshness)
 * @param normalizedReport the normalized detail (opaque map; vendor quirks removed)
 */
public record CanonicalBureauResult(
        BureauType type,
        int score,
        String grade,
        String reportId,
        String source,
        String fetchedAt,
        Map<String, Object> normalizedReport) {

    public CanonicalBureauResult {
        normalizedReport = normalizedReport == null ? Map.of() : Map.copyOf(normalizedReport);
    }

    /** Flat map for the capability response (what scoring/audit consume). */
    public Map<String, Object> toMap() {
        return Map.of(
                "type", type.name(),
                "score", score,
                "grade", grade == null ? "" : grade,
                "reportId", reportId == null ? "" : reportId,
                "source", source == null ? "" : source,
                "fetchedAt", fetchedAt == null ? "" : fetchedAt);
    }
}

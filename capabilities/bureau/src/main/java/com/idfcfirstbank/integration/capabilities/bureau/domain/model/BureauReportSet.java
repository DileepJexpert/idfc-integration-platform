package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * The merged result of a fan-out across bureaus. Carries every bureau's canonical
 * result; downstream scoring reads the merged set, but the demo branch keys on a
 * single {@code primaryScore} (CIBIL if present, else the most conservative —
 * lowest — score) so a multi-bureau pull never silently inflates the score.
 */
public record BureauReportSet(List<CanonicalBureauResult> results) {

    public BureauReportSet {
        results = List.copyOf(results);
    }

    public CanonicalBureauResult primary() {
        return results.stream()
                .filter(r -> r.type() == BureauType.CIBIL)
                .findFirst()
                .orElseGet(() -> results.stream()
                        .min(Comparator.comparingInt(CanonicalBureauResult::score))
                        .orElseThrow(() -> new IllegalStateException("no bureau results")));
    }

    public int primaryScore() {
        return primary().score();
    }

    public String primaryGrade() {
        return primary().grade();
    }

    public String primaryReportId() {
        return primary().reportId();
    }
}

package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.time.Instant;

/**
 * One bureau's canonical result (§2.3). Produced by a vendor adapter after it
 * translates the vendor response into canonical form.
 *
 * @param type      which bureau this is for
 * @param score     normalized score (nullable — not every pull returns a score)
 * @param report    normalized report (nullable — e.g. a score-only pull)
 * @param rawRef    reference/handle to the retained raw vendor payload (claim-check
 *                  style; nullable). The raw body itself is NOT inlined here.
 * @param fetchedAt when this bureau was fetched
 * @param source    the adapter/vendor identity that produced it (e.g. "cibil", "scorecard-infra")
 */
public record BureauResult(
        BureauType type,
        BureauScore score,
        BureauReport report,
        String rawRef,
        Instant fetchedAt,
        String source) {
}

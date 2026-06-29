package com.idfcfirstbank.integration.capabilities.bureau.parity;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauResult;

import java.util.Map;
import java.util.TreeMap;

/**
 * The parity-relevant projection of a bureau fetch (capability §F-equivalent).
 * Contains ONLY fields that must match the old duplicated calls' output. The
 * allowlist is encoded by ABSENCE: {@code fetchedAt} (timestamps), {@code rawRef}
 * (raw-blob handle/ordering) and {@code source} (vendor label) are deliberately
 * excluded, so they can never register as a parity diff.
 */
public record BureauParitySnapshot(String status, Map<String, ResultProjection> byType) {

    /** Normalized, vendor-neutral fields per bureau type (the parity-relevant set). */
    public record ResultProjection(
            Integer scoreValue,
            String scoreModel,
            String segment,
            Integer totalTradelines,
            String totalOutstanding,
            String overdueAmount) {
    }

    public static BureauParitySnapshot of(BureauFetchResponse response) {
        Map<String, ResultProjection> byType = new TreeMap<>();
        for (BureauResult r : response.bureauResults()) {
            byType.put(r.type().name(), new ResultProjection(
                    r.score() == null ? null : r.score().value(),
                    r.score() == null ? null : r.score().model(),
                    r.report() == null ? null : r.report().scoreSegment(),
                    r.report() == null ? null : r.report().totalTradelines(),
                    r.report() == null || r.report().totalOutstanding() == null
                            ? null : r.report().totalOutstanding().toPlainString(),
                    r.report() == null || r.report().overdueAmount() == null
                            ? null : r.report().overdueAmount().toPlainString()));
        }
        return new BureauParitySnapshot(response.status().name(), byType);
    }
}

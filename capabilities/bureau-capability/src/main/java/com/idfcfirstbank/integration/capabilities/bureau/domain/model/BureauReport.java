package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * The canonical, vendor-neutral bureau report — the normalized SUPERSET every
 * vendor adapter maps INTO (§2.3 {@code report{...normalized...}}). It carries
 * the universal bureau concepts (summary counts/amounts + tradelines +
 * enquiries); the precise field set is finalized during the harvest (step 3),
 * where each absorbed service's real response fields are mapped here. Adapters
 * never leak vendor-specific shapes past this type.
 */
public record BureauReport(
        String scoreSegment,
        int totalTradelines,
        int activeTradelines,
        BigDecimal totalOutstanding,
        BigDecimal overdueAmount,
        Integer oldestTradelineMonths,
        int enquiriesLast6Months,
        int writtenOffOrSettled,
        List<TradeLine> tradelines,
        List<Enquiry> enquiries) {

    public BureauReport {
        tradelines = tradelines == null ? List.of() : List.copyOf(tradelines);
        enquiries = enquiries == null ? List.of() : List.copyOf(enquiries);
    }
}

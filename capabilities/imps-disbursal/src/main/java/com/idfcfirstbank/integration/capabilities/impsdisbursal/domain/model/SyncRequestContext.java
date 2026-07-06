package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model;

/**
 * The trace/authz context carried alongside a SYNC request — recorded exactly as
 * the async lane records its envelope identity, so a sync call is just as
 * traceable. {@code source} (INDMONEY, SAVEIN, …) is the DIGITAL PARTNER: a config
 * attribute for trace/authz/rate-limit, <b>never</b> a routing key — every partner
 * resolves to the SAME lane and the SAME code path.
 */
public record SyncRequestContext(String correlationId, String transactionId, String source) {

    public static SyncRequestContext of(String correlationId, String transactionId, String source) {
        return new SyncRequestContext(
                blankToNull(correlationId), blankToNull(transactionId), blankToNull(source));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

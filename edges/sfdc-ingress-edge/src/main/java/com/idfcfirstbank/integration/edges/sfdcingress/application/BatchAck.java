package com.idfcfirstbank.integration.edges.sfdcingress.application;

import java.util.List;

/**
 * The outcome of ingesting one SOAP batch. {@code accepted} is the all-or-nothing
 * SOAP {@code <Ack>} value: true ONLY if every notification was durably accepted
 * (published, deduped, or permanently DLQ'd). A single transient failure makes the
 * whole batch {@code accepted=false} so SFDC resends the ENTIRE message — the
 * per-{@code Notification/Id} idempotency then skips the ones that already landed
 * (normalisation spec §5).
 */
public record BatchAck(int total, int acknowledged, boolean accepted, List<EdgeResult> results) {

    public BatchAck {
        results = results == null ? List.of() : List.copyOf(results);
    }
}

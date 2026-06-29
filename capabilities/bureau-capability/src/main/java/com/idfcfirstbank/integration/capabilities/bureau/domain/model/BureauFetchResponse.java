package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.util.List;

/**
 * Canonical OUT response (§2.3): one {@link BureauResult} per requested bureau,
 * an overall {@link FetchStatus}, and the trace {@code correlationId}.
 */
public record BureauFetchResponse(
        List<BureauResult> bureauResults,
        FetchStatus status,
        String correlationId) {

    public BureauFetchResponse {
        bureauResults = bureauResults == null ? List.of() : List.copyOf(bureauResults);
    }
}

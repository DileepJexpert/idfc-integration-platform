package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.util.List;

/**
 * Canonical IN request (§2.3) — the SUPERSET of what the absorbed services sent.
 * Callers ask for one or more {@code bureauTypes}; the capability fans out and
 * normalizes. {@code consentRef} ties the pull to a DPDP consent record (the
 * exact contents are an open compliance input — PART 6). {@code correlationId}
 * is trace only.
 */
public record BureauFetchRequest(
        Applicant applicant,
        List<BureauType> bureauTypes,
        Purpose purpose,
        String consentRef,
        String correlationId) {

    public BureauFetchRequest {
        bureauTypes = bureauTypes == null ? List.of() : List.copyOf(bureauTypes);
    }
}

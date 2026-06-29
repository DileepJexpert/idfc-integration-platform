package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * The per-vendor query handed to each OUT port — the slice of a
 * {@link BureauFetchRequest} a single bureau adapter needs (applicant + purpose
 * + consent + trace), without the {@code bureauTypes} fan-out list. Keeps the
 * OUT port signatures stable as the request evolves.
 */
public record BureauQuery(Applicant applicant, Purpose purpose, String consentRef, String correlationId) {

    public static BureauQuery from(BureauFetchRequest request) {
        return new BureauQuery(request.applicant(), request.purpose(),
                request.consentRef(), request.correlationId());
    }
}

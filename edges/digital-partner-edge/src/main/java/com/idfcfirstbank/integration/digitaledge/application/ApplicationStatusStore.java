package com.idfcfirstbank.integration.digitaledge.application;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-application status for the partner status endpoint and the callback
 * push-back. Populated at publish time (PENDING, with the owning partner — which
 * is NOT in the shared envelope) and updated when the engine's decision returns.
 * In-memory for the demo; a durable store is a later concern.
 */
@Component
public class ApplicationStatusStore {

    public record Status(String applicationId, String applicationRef, String partner,
                         String outcome, String loanId) {
    }

    // Indexed by both applicationRef (decision lookup) and applicationId (status endpoint).
    private final ConcurrentHashMap<String, Status> byApplicationRef = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Status> byApplicationId = new ConcurrentHashMap<>();

    public void register(String applicationRef, String applicationId, String partner) {
        Status status = new Status(applicationId, applicationRef, partner, "PENDING", null);
        byApplicationRef.put(applicationRef, status);
        byApplicationId.put(applicationId, status);
    }

    /** Apply a returned decision; returns the updated status (with the owning partner). */
    public Optional<Status> recordDecision(String applicationRef, String outcome, String loanId) {
        Status existing = byApplicationRef.get(applicationRef);
        if (existing == null) {
            return Optional.empty();
        }
        Status updated = new Status(existing.applicationId(), applicationRef, existing.partner(), outcome, loanId);
        byApplicationRef.put(applicationRef, updated);
        byApplicationId.put(existing.applicationId(), updated);
        return Optional.of(updated);
    }

    public Optional<Status> byApplicationId(String applicationId) {
        return Optional.ofNullable(byApplicationId.get(applicationId));
    }
}

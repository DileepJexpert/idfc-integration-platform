package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/**
 * Parsed §7 {@code policies.circuitBreaker} for one task node (T2).
 * {@code failureThreshold} is a CONSECUTIVE-failure count (the loader validator
 * requires an integral value ≥ 1); after {@code openDurationMillis} the breaker
 * half-opens and lets {@code halfOpenTrials} probe dispatches through — one
 * success closes it, one failure re-opens it. Breaker state is PER ENGINE
 * REPLICA and in-memory (documented v1 limitation): it protects a struggling
 * capability from a hammering replica, not from the whole fleet.
 */
public record CircuitBreakerSpec(int failureThreshold, long openDurationMillis, int halfOpenTrials) {

    public CircuitBreakerSpec {
        halfOpenTrials = halfOpenTrials <= 0 ? 1 : halfOpenTrials;
    }
}

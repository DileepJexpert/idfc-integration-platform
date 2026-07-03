package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.Set;

/**
 * Parsed §7 {@code policies.retry} for one task node (T2). {@code maxAttempts}
 * counts EVERY dispatch including the first; retry classes are the shared
 * {@code ErrorClass} names the capability stamps on its ERROR response —
 * {@code TRANSIENT} and {@code AMBIGUOUS} only ({@code PERMANENT} is never
 * retryable and the loader validator rejects it; an UNCLASSIFIED error is
 * treated as AMBIGUOUS, so it retries only when the author explicitly opted
 * ambiguous outcomes in — a possibly-completed write must never be blind-retried).
 */
public record RetrySpec(
        int maxAttempts,
        long backoffBaseMillis,
        long backoffMaxMillis,
        boolean jitter,
        Set<String> retryOn) {

    public RetrySpec {
        retryOn = retryOn == null || retryOn.isEmpty() ? Set.of("TRANSIENT") : Set.copyOf(retryOn);
    }
}

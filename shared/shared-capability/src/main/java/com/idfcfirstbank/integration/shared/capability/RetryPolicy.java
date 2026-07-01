package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

import java.util.Set;

/**
 * Per-operation retry policy (verification spec v2 §C). The NODE declares intent
 * ({@code retryOn}, {@code maxAttempts}, {@code idempotent}); the retry-policy engine
 * enforces it. {@code idempotent} is the load-bearing flag: it lets AMBIGUOUS failures
 * be retried for reads (verifications) but NEVER for possible writes (mandate/payments).
 */
public record RetryPolicy(Set<ErrorClass> retryOn, int maxAttempts, Backoff backoff, boolean idempotent) {

    public RetryPolicy {
        retryOn = retryOn == null ? Set.of() : Set.copyOf(retryOn);
        maxAttempts = maxAttempts <= 0 ? 1 : maxAttempts;
        backoff = backoff == null ? Backoff.fixed(0) : backoff;
    }

    /** The common read policy: retry only TRANSIENT, idempotent so AMBIGUOUS is safe too. */
    public static RetryPolicy idempotentReads(int maxAttempts, Backoff backoff) {
        return new RetryPolicy(Set.of(ErrorClass.TRANSIENT), maxAttempts, backoff, true);
    }

    /** A write policy: retry TRANSIENT only; AMBIGUOUS is NOT retried (no idempotency). */
    public static RetryPolicy nonIdempotentWrites(int maxAttempts, Backoff backoff) {
        return new RetryPolicy(Set.of(ErrorClass.TRANSIENT), maxAttempts, backoff, false);
    }
}

package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.Classified;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

import java.util.function.Supplier;

/**
 * The classified retry-policy engine (verification spec v2 §C) — replaces blind fixed-count
 * retry. Runs an operation; on a {@link Classified} failure it resolves the EFFECTIVE class
 * (AMBIGUOUS -> TRANSIENT iff the policy is idempotent, else PERMANENT) and retries only
 * when that class is in {@code retryOn} and attempts remain, sleeping the exponential+jitter
 * backoff between tries. On exhaustion/non-retryable it rethrows the last failure for the
 * caller to DLQ+notify. An unclassified exception is treated as PERMANENT (never blind-retried).
 */
public final class RetryExecutor {

    private final Sleeper sleeper;

    public RetryExecutor(Sleeper sleeper) {
        this.sleeper = sleeper;
    }

    public static RetryExecutor withRealSleep() {
        return new RetryExecutor(Sleeper.REAL);
    }

    public <T> T execute(RetryPolicy policy, Supplier<T> operation) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException e) {
                last = e;
                ErrorClass effective = effectiveClass(e, policy);
                boolean canRetry = policy.retryOn().contains(effective) && attempt < policy.maxAttempts();
                if (!canRetry) {
                    break;
                }
                sleeper.sleep(policy.backoff().delayMillis(attempt));
            }
        }
        throw last;
    }

    /** AMBIGUOUS resolves by idempotency; anything unclassified is PERMANENT. */
    private static ErrorClass effectiveClass(RuntimeException e, RetryPolicy policy) {
        ErrorClass raw = e instanceof Classified c ? c.errorClass() : ErrorClass.PERMANENT;
        if (raw == ErrorClass.AMBIGUOUS) {
            return policy.idempotent() ? ErrorClass.TRANSIENT : ErrorClass.PERMANENT;
        }
        return raw;
    }
}

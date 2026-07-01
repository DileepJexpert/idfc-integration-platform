package com.idfcfirstbank.integration.shared.capability;

import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with optional FULL JITTER (verification spec v2 §C.2): the delay
 * for attempt n is {@code base * 2^(n-1)} capped at {@code max}, and with jitter the
 * actual sleep is a random value in {@code [0, capped]}. Jitter is mandatory in
 * production so a downstream outage cannot trigger a synchronized retry-storm (the
 * Diwali thundering-herd) from many concurrent requests. Randomness is injected so
 * tests are deterministic.
 */
public final class Backoff {

    private final long baseMillis;
    private final long maxMillis;
    private final boolean jitter;
    private final DoubleSupplier random;   // in [0,1)

    public Backoff(long baseMillis, long maxMillis, boolean jitter, DoubleSupplier random) {
        this.baseMillis = Math.max(0, baseMillis);
        this.maxMillis = maxMillis <= 0 ? Long.MAX_VALUE : maxMillis;
        this.jitter = jitter;
        this.random = random;
    }

    /** Exponential + full jitter, backed by ThreadLocalRandom (production). */
    public static Backoff exponential(long baseMillis, long maxMillis, boolean jitter) {
        return new Backoff(baseMillis, maxMillis, jitter,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextDouble());
    }

    /** Fixed, no-jitter (mostly for tests / trivial policies). */
    public static Backoff fixed(long millis) {
        return new Backoff(millis, millis, false, () -> 0.0);
    }

    /** Delay before the given (1-based) attempt's RETRY. */
    public long delayMillis(int attempt) {
        int shift = Math.min(Math.max(0, attempt - 1), 30);
        long exp = baseMillis << shift;                 // base * 2^(attempt-1)
        long capped = Math.min(exp < 0 ? Long.MAX_VALUE : exp, maxMillis);
        if (!jitter) {
            return capped;
        }
        double r = Math.max(0.0, Math.min(1.0, random.getAsDouble()));
        return (long) (capped * r);                     // full jitter: [0, capped]
    }
}

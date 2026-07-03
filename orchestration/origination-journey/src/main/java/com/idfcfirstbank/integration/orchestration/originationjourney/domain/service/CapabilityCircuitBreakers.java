package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.CircuitBreakerSpec;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * T2 per-capability circuit breakers, keyed by capability key. Deterministic
 * (injected {@link Clock}) and PER ENGINE REPLICA in-memory — a v1 limitation
 * documented on {@link CircuitBreakerSpec}: it stops THIS replica hammering a
 * struggling capability; it is not a fleet-wide brake.
 *
 * <p>State machine per capability: CLOSED → (threshold consecutive failures)
 * → OPEN → (openDuration elapses) → HALF-OPEN with {@code halfOpenTrials}
 * probe permits → one success CLOSES, one failure RE-OPENS.
 */
public final class CapabilityCircuitBreakers {

    private static final class BreakerState {
        int consecutiveFailures;
        Instant openedAt;          // null = closed
        int halfOpenPermitsUsed;
    }

    private final Clock clock;
    private final Map<String, BreakerState> byCapability = new ConcurrentHashMap<>();

    public CapabilityCircuitBreakers(Clock clock) {
        this.clock = clock;
    }

    /** A no-op registry for T1-style tests: everything always dispatches. */
    public static CapabilityCircuitBreakers alwaysClosed() {
        return new CapabilityCircuitBreakers(Clock.systemUTC());
    }

    /**
     * May a request to this capability go out right now? Consumes a half-open
     * probe permit when in the half-open window.
     */
    public synchronized boolean allowDispatch(String capabilityKey, CircuitBreakerSpec spec) {
        if (spec == null) {
            return true;
        }
        BreakerState s = byCapability.computeIfAbsent(capabilityKey, k -> new BreakerState());
        if (s.openedAt == null) {
            return true;
        }
        Instant halfOpenAt = s.openedAt.plusMillis(spec.openDurationMillis());
        if (clock.instant().isBefore(halfOpenAt)) {
            return false; // OPEN: fail fast
        }
        // HALF-OPEN: limited probes.
        if (s.halfOpenPermitsUsed < spec.halfOpenTrials()) {
            s.halfOpenPermitsUsed++;
            return true;
        }
        return false;
    }

    /** A capability response arrived: success closes / failure counts toward opening. */
    public synchronized void record(String capabilityKey, CircuitBreakerSpec spec, boolean success) {
        if (spec == null) {
            return;
        }
        BreakerState s = byCapability.computeIfAbsent(capabilityKey, k -> new BreakerState());
        if (success) {
            s.consecutiveFailures = 0;
            s.openedAt = null;
            s.halfOpenPermitsUsed = 0;
            return;
        }
        s.consecutiveFailures++;
        if (s.openedAt != null || s.consecutiveFailures >= spec.failureThreshold()) {
            // Threshold reached, or a half-open probe failed: (re)open NOW.
            s.openedAt = clock.instant();
            s.halfOpenPermitsUsed = 0;
        }
    }
}

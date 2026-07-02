package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * In-memory {@link CapabilityIdempotencyStore} (default; single-instance demo).
 *
 * <p>Two correctness properties beyond a naive {@code computeIfAbsent}:
 * <ul>
 *   <li><b>Only OK responses are retained.</b> A failed compute (ERROR response)
 *       is NOT cached, so a later redelivery re-executes — which is exactly what
 *       the {@code TRANSIENT} error class exists for. Caching an ERROR would pin a
 *       one-off vendor blip forever and defeat every retry.</li>
 *   <li><b>Single-flight, compute runs outside the map lock.</b> Concurrent
 *       callers with the same key share ONE computation (at-most-once, the
 *       exactly-once primitive) but the vendor call does not run inside a
 *       {@code ConcurrentHashMap} bin lock, so unrelated keys never block behind
 *       a slow capability.</li>
 * </ul>
 *
 * <p>Cached OK entries carry a TTL and are evicted lazily (on read, and by an
 * opportunistic sweep on write), so the map cannot grow without bound. A durable
 * Aerospike CREATE_ONLY variant swaps in behind the same port for multi-instance.
 */
public class InMemoryCapabilityIdempotencyStore implements CapabilityIdempotencyStore {

    /** Default retention for a cached OK response. */
    public static final Duration DEFAULT_TTL = Duration.ofHours(6);
    /** Sweep expired entries once the map grows past this. */
    private static final int SWEEP_THRESHOLD = 1_024;

    private record Entry(CapabilityResponse response, long expiresAtMillis) {
        boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<CapabilityResponse>> inFlight =
            new ConcurrentHashMap<>();
    private final Clock clock;
    private final long ttlMillis;

    public InMemoryCapabilityIdempotencyStore() {
        this(DEFAULT_TTL, Clock.systemUTC());
    }

    public InMemoryCapabilityIdempotencyStore(Duration ttl, Clock clock) {
        this.ttlMillis = ttl.toMillis();
        this.clock = clock;
    }

    @Override
    public CapabilityResponse executeOnce(String key, Supplier<CapabilityResponse> compute) {
        CapabilityResponse cached = cachedIfFresh(key);
        if (cached != null) {
            return cached;
        }

        // Single-flight: the first caller for this key computes; concurrent callers
        // await the SAME result instead of re-executing.
        CompletableFuture<CapabilityResponse> mine = new CompletableFuture<>();
        CompletableFuture<CapabilityResponse> existing = inFlight.putIfAbsent(key, mine);
        if (existing != null) {
            return existing.join();
        }

        try {
            CapabilityResponse result = compute.get();
            // Cache ONLY successful results; failures are re-attemptable on redelivery.
            if (result != null && result.status() == CapabilityStatus.OK) {
                sweepIfLarge();
                cache.put(key, new Entry(result, clock.millis() + ttlMillis));
            }
            mine.complete(result);
            return result;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private CapabilityResponse cachedIfFresh(String key) {
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.millis())) {
            cache.remove(key, entry);
            return null;
        }
        return entry.response();
    }

    private void sweepIfLarge() {
        if (cache.size() < SWEEP_THRESHOLD) {
            return;
        }
        long now = clock.millis();
        for (Iterator<Map.Entry<String, Entry>> it = cache.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().isExpired(now)) {
                it.remove();
            }
        }
    }
}

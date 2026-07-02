package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for Phase 1 item 3: the idempotency store caches ONLY OK
 * responses (so a TRANSIENT failure can be retried on redelivery instead of being
 * pinned forever), enforces a TTL, and still executes at-most-once per key under
 * concurrency.
 */
class InMemoryCapabilityIdempotencyStoreTest {

    /** A clock whose "now" the test can advance. */
    private static final class MutableClock extends Clock {
        private long millis;
        MutableClock(long startMillis) { this.millis = startMillis; }
        void advance(Duration d) { millis += d.toMillis(); }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static CapabilityResponse ok() {
        return new CapabilityResponse("ji-1", "corr-1", "n_x", "bureau",
                CapabilityStatus.OK, Map.of("k", "v"));
    }

    private static CapabilityResponse error() {
        return new CapabilityResponse("ji-1", "corr-1", "n_x", "bureau",
                CapabilityStatus.ERROR, Map.of(), ErrorClass.TRANSIENT);
    }

    @Test
    void okResponseIsCachedAndComputedOnce() {
        var store = new InMemoryCapabilityIdempotencyStore(Duration.ofHours(1), new MutableClock(0));
        AtomicInteger runs = new AtomicInteger();

        CapabilityResponse first = store.executeOnce("k1", () -> { runs.incrementAndGet(); return ok(); });
        CapabilityResponse second = store.executeOnce("k1", () -> { runs.incrementAndGet(); return ok(); });

        assertThat(runs.get()).isEqualTo(1);
        assertThat(second).isSameAs(first);
    }

    @Test
    void errorResponseIsNotCachedSoRedeliveryReExecutes() {
        var store = new InMemoryCapabilityIdempotencyStore(Duration.ofHours(1), new MutableClock(0));
        AtomicInteger runs = new AtomicInteger();

        store.executeOnce("k1", () -> { runs.incrementAndGet(); return error(); });
        store.executeOnce("k1", () -> { runs.incrementAndGet(); return error(); });

        // The whole point: a TRANSIENT failure is retryable, not pinned.
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void cachedOkEntryExpiresAfterTtl() {
        MutableClock clock = new MutableClock(0);
        var store = new InMemoryCapabilityIdempotencyStore(Duration.ofMinutes(30), clock);
        AtomicInteger runs = new AtomicInteger();

        store.executeOnce("k1", () -> { runs.incrementAndGet(); return ok(); });
        clock.advance(Duration.ofMinutes(31));
        store.executeOnce("k1", () -> { runs.incrementAndGet(); return ok(); });

        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void concurrentIdenticalRequestsExecuteOnce() throws Exception {
        var store = new InMemoryCapabilityIdempotencyStore(Duration.ofHours(1), new MutableClock(0));
        AtomicInteger runs = new AtomicInteger();
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        store.executeOnce("k1", () -> {
                            runs.incrementAndGet();
                            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
                            return ok();
                        });
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
        } finally {
            pool.shutdownNow();
        }
        assertThat(runs.get()).isEqualTo(1);
    }
}

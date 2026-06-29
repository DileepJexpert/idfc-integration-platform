package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.aerospike;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.ApplicationKey;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.support.AerospikeTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE correctness gate (punch list §D): the atomic insert-if-absent under REAL
 * concurrency against a REAL Aerospike. Many threads race to claim the SAME key
 * at once; CREATE_ONLY must yield EXACTLY ONE winner — no double-book.
 *
 * <p>An in-memory fake is explicitly NOT acceptable here (a map would pass the
 * atomicity assertion falsely), so this drives a Testcontainers Aerospike via
 * {@link AerospikeTestSupport}. Tagged {@code integration} — Docker required,
 * excluded from the fast build.
 */
@Tag("integration")
class AerospikeIdempotencyStoreConcurrencyIT {

    private static final int THREADS = 32;
    private static GenericContainer<?> aerospike;
    private static AerospikeIdempotencyStore store;

    @BeforeAll
    static void startStore() {
        aerospike = AerospikeTestSupport.newContainer();
        aerospike.start();
        store = new AerospikeIdempotencyStore(
                AerospikeTestSupport.connect(),
                AerospikeTestSupport.NAMESPACE, AerospikeTestSupport.RECORD_SET,
                AerospikeTestSupport.APP_POINTER_SET, 3600, Clock.systemUTC());
    }

    @AfterAll
    static void stop() {
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Test
    void concurrentIdenticalInserts_yieldExactlyOneWinner() throws Exception {
        IdempotencyRecord record = IdempotencyRecord.newReceived(
                "ntf-concurrent-1", "rec-1", "APP-1", "ORG1", "corr-1", Instant.now());

        AtomicInteger inserted = raceAndCount(THREADS,
                () -> store.insertIfAbsent(record) == IdempotencyStorePort.InsertOutcome.INSERTED);

        assertThat(inserted.get())
                .as("exactly ONE concurrent CREATE_ONLY insert wins")
                .isEqualTo(1);
    }

    @Test
    void concurrentCompositeKeyLinks_yieldExactlyOneOwner() throws Exception {
        ApplicationKey key = ApplicationKey.of("rec-2", "APP-2");

        AtomicInteger linked = raceAndCount(THREADS, () -> {
            // Each contender presents its own (distinct) owning notificationId.
            String owner = "ntf-" + Thread.currentThread().getId();
            return store.linkApplication(key, owner) == IdempotencyStorePort.LinkOutcome.LINKED;
        });

        assertThat(linked.get())
                .as("exactly ONE concurrent CREATE_ONLY application-link wins (the single owner)")
                .isEqualTo(1);
    }

    /** Release {@code threads} simultaneously (a barrier) and count how many saw {@code true}. */
    private AtomicInteger raceAndCount(int threads, java.util.function.BooleanSupplier action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        AtomicInteger winners = new AtomicInteger();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();           // maximize contention: everyone fires together
                    if (action.getAsBoolean()) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
            return winners;
        } finally {
            pool.shutdownNow();
        }
    }
}

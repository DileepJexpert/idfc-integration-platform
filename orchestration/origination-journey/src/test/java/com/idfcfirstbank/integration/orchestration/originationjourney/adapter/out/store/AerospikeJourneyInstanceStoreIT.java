package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.AerospikeTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durable journey-state round-trip against a REAL Aerospike: a journey instance
 * with accumulated run state (collected results + completed/dispatched node sets
 * + status) is saved and re-read, proving the engine resumes exactly where it
 * left off across the async hops / restarts. Tagged {@code integration}.
 */
@Tag("integration")
class AerospikeJourneyInstanceStoreIT {

    private static GenericContainer<?> aerospike;
    private static AerospikeJourneyInstanceStore store;

    @BeforeAll
    static void start() {
        aerospike = AerospikeTestSupport.newContainer();
        aerospike.start();
        store = new AerospikeJourneyInstanceStore(
                AerospikeTestSupport.connect(), new ObjectMapper(),
                AerospikeTestSupport.NAMESPACE, AerospikeTestSupport.INSTANCE_SET, 3600);
    }

    @AfterAll
    static void stop() {
        if (aerospike != null) {
            aerospike.stop();
        }
    }

    @Test
    void savesAndRestoresFullRunState() {
        JourneyInstance original = new JourneyInstance(
                "ji-it-1", "corr-it", "loan-origination", "APP-1", Map.of("pan", "ABCDE1234F"));
        original.markDispatched("n_customer");
        original.recordResult("n_customer", "customer-party", "context.customer", Map.of("crn", "CRN-1"));
        original.recordResult("n_bureau", "bureau", "context.bureau", Map.of("bureauScore", 780));
        original.complete();

        store.save(original);
        JourneyInstance restored = store.find("ji-it-1").orElseThrow();

        assertThat(restored.correlationId()).isEqualTo("corr-it");
        assertThat(restored.journeyKey()).isEqualTo("loan-origination");
        assertThat(restored.applicationRef()).isEqualTo("APP-1");
        assertThat(restored.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(restored.isDispatched("n_customer")).isTrue();
        assertThat(restored.isCompleted("n_bureau")).isTrue();
        assertThat(restored.collectedResults()).containsKey("bureau");
        assertThat(((Map<?, ?>) restored.collectedResults().get("bureau")).get("bureauScore"))
                .isEqualTo(780);
    }

    @Test
    void findReturnsEmptyForUnknownInstance() {
        assertThat(store.find("ji-does-not-exist")).isEmpty();
    }

    /**
     * The durable half of the exactly-once-start gate: 32 threads race
     * {@code insertIfAbsent} on the SAME instance id; the Aerospike CREATE_ONLY
     * write (with KEY_BUSY hot-key retry) must admit exactly ONE winner.
     */
    @Test
    void concurrentInsertIfAbsentAdmitsExactlyOneWinner() throws Exception {
        String id = "ji-race-1";
        int threads = 32;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.atomic.AtomicInteger winners = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    JourneyInstance inst = new JourneyInstance(
                            id, "corr-race", "loan-origination", "APP-R", Map.of("pan", "X"));
                    if (store.insertIfAbsent(inst)) {
                        winners.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // a non-KEY_EXISTS failure would surface as a missing winner
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();

        assertThat(winners.get()).as("exactly one CREATE_ONLY winner").isEqualTo(1);
        assertThat(store.find(id)).isPresent();
    }
}

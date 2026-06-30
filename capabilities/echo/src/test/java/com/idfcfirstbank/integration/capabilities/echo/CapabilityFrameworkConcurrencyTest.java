package com.idfcfirstbank.integration.capabilities.echo;

import com.idfcfirstbank.integration.shared.capability.Capability;
import com.idfcfirstbank.integration.shared.capability.CapabilityDispatcher;
import com.idfcfirstbank.integration.shared.capability.CapabilityOperation;
import com.idfcfirstbank.integration.shared.capability.InMemoryCapabilityIdempotencyStore;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE capability-framework gate (BRD §9 step 1): a concurrent-identical-request
 * must execute the operation EXACTLY ONCE. 32 threads hand the dispatcher the
 * SAME request (same idempotency key); the compute-once idempotency store admits
 * one execution and every caller gets the same response. This is the homogeneous
 * exactly-once guarantee every capability inherits.
 */
class CapabilityFrameworkConcurrencyTest {

    @Test
    void concurrentIdenticalRequestsExecuteExactlyOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Capability counting = new Capability() {
            @Override public String key() { return "echo"; }
            @Override public List<CapabilityOperation> operations() {
                return List.of(new CapabilityOperation() {
                    @Override public String name() { return "echo"; }
                    @Override public Map<String, Object> execute(CapabilityRequest req) {
                        executions.incrementAndGet();
                        return Map.of("echo", req.payload());
                    }
                });
            }
        };
        CapabilityDispatcher dispatcher =
                new CapabilityDispatcher(counting, new InMemoryCapabilityIdempotencyStore());

        // Same (runId,nodeId) => same idempotency key.
        CapabilityRequest req = new CapabilityRequest(
                "ji-1", "corr-1", "echo", "n_echo", Map.of("hello", "world"), Map.of(),
                "echo", "ji-1:n_echo");

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<CapabilityResponse> responses = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();
                    responses.add(dispatcher.handle(req));
                } catch (Exception ignored) {
                    // a failure surfaces as a missing/!=1 execution below
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();

        assertThat(executions.get()).as("operation executed exactly once").isEqualTo(1);
        assertThat(responses).hasSize(threads);
        assertThat(responses).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(CapabilityStatus.OK);
            assertThat(r.result()).containsKey("echo");
        });
        // All callers observed the identical (cached) response.
        assertThat(responses.stream().distinct().count()).isEqualTo(1);
    }

    @Test
    void echoCapabilityReturnsThePayload() {
        CapabilityDispatcher dispatcher =
                new CapabilityDispatcher(new EchoCapability(), new InMemoryCapabilityIdempotencyStore());
        CapabilityResponse resp = dispatcher.handle(new CapabilityRequest(
                "ji-2", "corr-2", "echo", "n_echo", Map.of("k", "v"), Map.of(), "echo", null));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.OK);
        assertThat(resp.result().get("echo")).isEqualTo(Map.of("k", "v"));
    }

    @Test
    void unknownOperationIsAPermanentError() {
        CapabilityDispatcher dispatcher =
                new CapabilityDispatcher(new EchoCapability(), new InMemoryCapabilityIdempotencyStore());
        CapabilityResponse resp = dispatcher.handle(new CapabilityRequest(
                "ji-3", "corr-3", "echo", "n_echo", Map.of(), Map.of(), "does-not-exist", null));
        assertThat(resp.status()).isEqualTo(CapabilityStatus.ERROR);
        assertThat(resp.errorClass().name()).isEqualTo("PERMANENT");
    }
}

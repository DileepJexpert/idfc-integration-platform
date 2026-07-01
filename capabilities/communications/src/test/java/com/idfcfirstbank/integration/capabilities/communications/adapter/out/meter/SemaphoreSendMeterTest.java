package com.idfcfirstbank.integration.capabilities.communications.adapter.out.meter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Backpressure proof: a burst of OTP sends (the Diwali surge shape) never exceeds
 * the CommsHub concurrency cap N — the shared bank resource is protected — while
 * still overlapping (real concurrency, not serialisation). All sends complete.
 */
class SemaphoreSendMeterTest {

    @Test
    void aBurstNeverExceedsTheCapButStillOverlaps() throws Exception {
        int cap = 3;
        int burst = 40;
        SemaphoreSendMeter meter = new SemaphoreSendMeter(cap);
        AtomicInteger completed = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(burst);
        CyclicBarrier barrier = new CyclicBarrier(burst);
        CountDownLatch done = new CountDownLatch(burst);
        for (int i = 0; i < burst; i++) {
            pool.submit(() -> {
                try {
                    barrier.await();                       // maximise contention
                    meter.meter(() -> {
                        try { Thread.sleep(15); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        completed.incrementAndGet();
                    });
                } catch (Exception e) {
                    // ignore
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        pool.shutdownNow();

        assertThat(meter.maxObservedConcurrency())
                .as("shared CommsHub concurrency capped at N").isLessThanOrEqualTo(cap);
        assertThat(meter.maxObservedConcurrency())
                .as("but genuinely overlapped, not serialised").isGreaterThan(1);
        assertThat(completed.get()).as("the whole burst still drained").isEqualTo(burst);
        assertThat(meter.totalMetered()).isEqualTo(burst);
    }
}

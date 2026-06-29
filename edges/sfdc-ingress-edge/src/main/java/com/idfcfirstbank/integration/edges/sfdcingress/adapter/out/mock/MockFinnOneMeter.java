package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock;

import com.idfcfirstbank.integration.edges.sfdcingress.config.EdgeProperties;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.FinnOneMeterPort;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slice 1 backpressure harness (§G) standing in for the FinnOne stored proc. It
 * enforces a hard concurrency cap N (a {@link Semaphore}) and records the maximum
 * observed concurrency so the 10x-burst test can assert FinnOne concurrency never
 * exceeds N (bounded by the consumer's thread pool) yet actually overlapped
 * (>1 = real backpressure, not serialization). NOT a real FinnOne integration.
 *
 * <p>Each call holds a brief, fixed "stored-proc" delay so a burst genuinely
 * overlaps; the backlog beyond N waits as Kafka consumer lag and drains to zero.
 */
@Component
public class MockFinnOneMeter implements FinnOneMeterPort {

    private static final long STORED_PROC_MILLIS = 75L;

    private final Semaphore permits;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObserved = new AtomicInteger();
    private final AtomicLong total = new AtomicLong();

    public MockFinnOneMeter(EdgeProperties properties) {
        this.permits = new Semaphore(properties.finnoneMaxConcurrency());
    }

    @Override
    public void invokeStoredProc(String applicationRef) {
        permits.acquireUninterruptibly();
        try {
            int now = inFlight.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            sleepQuietly();
        } finally {
            inFlight.decrementAndGet();
            total.incrementAndGet();
            permits.release();
        }
    }

    @Override
    public int maxObservedConcurrency() {
        return maxObserved.get();
    }

    @Override
    public long totalInvocations() {
        return total.get();
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(STORED_PROC_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

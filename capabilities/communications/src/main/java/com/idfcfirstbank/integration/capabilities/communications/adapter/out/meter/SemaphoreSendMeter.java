package com.idfcfirstbank.integration.capabilities.communications.adapter.out.meter;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SendMeterPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded-concurrency backpressure for the shared CommsHub: a {@link Semaphore} of
 * N permits gates the send path, so at most N sends hit CommsHub at once and a
 * burst waits (as consumer lag) instead of flooding it. Records the maximum
 * observed concurrency so a burst test can assert it never exceeds N.
 */
@Component
public class SemaphoreSendMeter implements SendMeterPort {

    private final Semaphore permits;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxObserved = new AtomicInteger();
    private final AtomicLong total = new AtomicLong();

    public SemaphoreSendMeter(
            @Value("${idfc.communications.commshub.max-concurrency:4}") int maxConcurrency) {
        this.permits = new Semaphore(Math.max(1, maxConcurrency));
    }

    @Override
    public void meter(Runnable send) {
        permits.acquireUninterruptibly();
        try {
            int now = inFlight.incrementAndGet();
            maxObserved.accumulateAndGet(now, Math::max);
            send.run();
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
    public long totalMetered() {
        return total.get();
    }
}

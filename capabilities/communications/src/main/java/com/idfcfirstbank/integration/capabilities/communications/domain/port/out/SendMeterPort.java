package com.idfcfirstbank.integration.capabilities.communications.domain.port.out;

/**
 * Backpressure meter for the shared CommsHub: caps how many sends this service
 * runs concurrently, so a burst (e.g. a Diwali OTP surge) queues as lag rather
 * than flooding a resource the whole bank depends on. Exposes the observed cap so
 * a burst test can prove concurrency never exceeds N. Mirrors the SFDC edge's
 * FinnOne backpressure harness — the same discipline for any shared resource.
 */
public interface SendMeterPort {
    void meter(Runnable send);

    int maxObservedConcurrency();

    long totalMetered();
}

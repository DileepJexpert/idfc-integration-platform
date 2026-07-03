package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * OUT port: persist journey-instance run state across the async capability hops.
 * The demo uses an in-memory impl; a later slice swaps in Aerospike (the org's
 * only datastore) with no change to the engine.
 */
public interface JourneyInstanceStore {
    /**
     * Atomically create the instance IF it does not already exist. Returns
     * {@code true} when THIS call created it (the caller is the single winner and
     * must start the journey), {@code false} when an instance with the same id is
     * already present (a redelivered / concurrent duplicate start — drop it).
     *
     * <p>This is the engine's exactly-once-start gate: combined with a
     * deterministic instance id derived from the inbound origination, it makes a
     * redelivered origination event (Kafka is at-least-once) a no-op rather than a
     * second run. Implementations MUST be atomic under concurrency (CAS / insert
     * if absent), not a read-then-write.
     */
    boolean insertIfAbsent(JourneyInstance instance);

    void save(JourneyInstance instance);

    Optional<JourneyInstance> find(String journeyInstanceId);

    /**
     * All RUNNING instances whose {@code startedAt} is before {@code cutoff} — the
     * candidates a liveness sweeper fails-and-notifies. Terminal (COMPLETED/FAILED)
     * instances are never returned. Used only by the scheduled sweeper, not the hot
     * path.
     */
    List<JourneyInstance> findRunningStartedBefore(Instant cutoff);

    /**
     * Every persisted instance — the ops read-API's source (B.3), never the hot
     * path. Terminal instances age out via the store TTL, which bounds the scan;
     * a filtered secondary index is the documented prod optimization (D15).
     */
    List<JourneyInstance> scanAll();
}

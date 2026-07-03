package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;

import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link JourneyInstanceStore} for the demo. Mirrors the durable
 * store's semantics faithfully so tests exhibit the same behavior as Aerospike:
 *
 * <ul>
 *   <li>{@code find} returns a SNAPSHOT copy (never an alias of live state),
 *       stamped with the version it was loaded at;</li>
 *   <li>{@code save} is a compare-and-set on that version — a concurrent writer
 *       who saved first makes this save throw
 *       {@link ConcurrentModificationException} (the caller's trigger is
 *       redelivered and reprocessed from fresh state), mirroring Aerospike's
 *       {@code EXPECT_GEN_EQUAL} generation check.</li>
 * </ul>
 */
public class InMemoryJourneyInstanceStore implements JourneyInstanceStore {

    private record Versioned(long version, JourneyInstance state) {
    }

    private final ConcurrentHashMap<String, Versioned> byId = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(JourneyInstance instance) {
        // Atomic: exactly one concurrent caller for a given id sees null returned.
        boolean created = byId.putIfAbsent(instance.journeyInstanceId(),
                new Versioned(1, snapshot(instance, 1))) == null;
        if (created) {
            instance.version(1);
        }
        return created;
    }

    @Override
    public void save(JourneyInstance instance) {
        long expected = instance.version();
        Versioned result = byId.compute(instance.journeyInstanceId(), (id, current) -> {
            long currentVersion = current == null ? 0 : current.version();
            if (currentVersion != expected) {
                throw new ConcurrentModificationException(
                        "journey instance " + id + " modified concurrently (expected v" + expected
                                + ", store has v" + currentVersion + ")");
            }
            long next = currentVersion + 1;
            return new Versioned(next, snapshot(instance, next));
        });
        instance.version(result.version());
    }

    @Override
    public Optional<JourneyInstance> find(String journeyInstanceId) {
        Versioned v = byId.get(journeyInstanceId);
        return v == null ? Optional.empty() : Optional.of(snapshot(v.state(), v.version()));
    }

    @Override
    public List<JourneyInstance> scanAll() {
        return byId.values().stream()
                .map(v -> snapshot(v.state(), v.version()))
                .collect(Collectors.toList());
    }

    @Override
    public List<JourneyInstance> findRunningStartedBefore(Instant cutoff) {
        return byId.values().stream()
                .filter(v -> v.state().status().isLive())
                .filter(v -> v.state().startedAt() != null && v.state().startedAt().isBefore(cutoff))
                .map(v -> snapshot(v.state(), v.version()))
                .collect(Collectors.toList());
    }

    /** Deep-enough copy: restore() rebuilds fresh collections from the source's views. */
    private static JourneyInstance snapshot(JourneyInstance i, long version) {
        return JourneyInstance.restore(
                i.journeyInstanceId(), i.correlationId(), i.journeyKey(), i.journeyVersion(),
                i.applicationRef(),
                i.payload(), i.startedAt(), version, i.collectedResults(), i.context(),
                i.completedNodeIds(), i.dispatchedNodeIds(), i.status(),
                i.pendingRequestNodeIds(), i.pendingDecision(),
                i.transitions(), i.endedAt(), i.terminalNodeId(), i.terminalOutcome(),
                i.sfdcNotified(),
                i.failedNodeIds(), i.dispatchAttempts(), i.compensationQueue(), i.compensationOf());
    }
}

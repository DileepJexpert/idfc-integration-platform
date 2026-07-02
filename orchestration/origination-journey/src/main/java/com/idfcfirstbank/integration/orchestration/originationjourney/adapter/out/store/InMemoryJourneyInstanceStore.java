package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link JourneyInstanceStore} for the demo. Single-engine-instance
 * only; a later slice replaces this with an Aerospike-backed store.
 */
public class InMemoryJourneyInstanceStore implements JourneyInstanceStore {

    private final ConcurrentHashMap<String, JourneyInstance> byId = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(JourneyInstance instance) {
        // Atomic: exactly one concurrent caller for a given id sees null returned.
        return byId.putIfAbsent(instance.journeyInstanceId(), instance) == null;
    }

    @Override
    public void save(JourneyInstance instance) {
        byId.put(instance.journeyInstanceId(), instance);
    }

    @Override
    public Optional<JourneyInstance> find(String journeyInstanceId) {
        return Optional.ofNullable(byId.get(journeyInstanceId));
    }

    @Override
    public List<JourneyInstance> findRunningStartedBefore(Instant cutoff) {
        return byId.values().stream()
                .filter(i -> i.status() == InstanceStatus.RUNNING)
                .filter(i -> i.startedAt() != null && i.startedAt().isBefore(cutoff))
                .collect(Collectors.toList());
    }
}

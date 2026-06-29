package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link JourneyInstanceStore} for the demo. Single-engine-instance
 * only; a later slice replaces this with an Aerospike-backed store.
 */
public class InMemoryJourneyInstanceStore implements JourneyInstanceStore {

    private final ConcurrentHashMap<String, JourneyInstance> byId = new ConcurrentHashMap<>();

    @Override
    public void save(JourneyInstance instance) {
        byId.put(instance.journeyInstanceId(), instance);
    }

    @Override
    public Optional<JourneyInstance> find(String journeyInstanceId) {
        return Optional.ofNullable(byId.get(journeyInstanceId));
    }
}

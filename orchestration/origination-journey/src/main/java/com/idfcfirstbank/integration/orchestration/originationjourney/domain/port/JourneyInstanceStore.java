package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;

import java.util.Optional;

/**
 * OUT port: persist journey-instance run state across the async capability hops.
 * The demo uses an in-memory impl; a later slice swaps in Aerospike (the org's
 * only datastore) with no change to the engine.
 */
public interface JourneyInstanceStore {
    void save(JourneyInstance instance);

    Optional<JourneyInstance> find(String journeyInstanceId);
}

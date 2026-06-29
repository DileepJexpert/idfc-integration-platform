package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;

/**
 * OUT port: publish a capability request. The Kafka adapter routes it to
 * {@code cap.<capabilityKey>.request.v1}; tests use an in-memory capturing impl.
 */
public interface CapabilityRequestPort {
    void publish(CapabilityRequest request);
}

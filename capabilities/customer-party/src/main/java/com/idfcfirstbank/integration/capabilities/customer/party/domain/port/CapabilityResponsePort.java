package com.idfcfirstbank.integration.capabilities.customer.party.domain.port;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;

/**
 * OUT port to publish this capability's {@link CapabilityResponse} back to the
 * engine. The Kafka adapter routes it to {@code cap.customer-party.response.v1};
 * tests use a capturing impl.
 */
public interface CapabilityResponsePort {
    void publish(CapabilityResponse response);
}

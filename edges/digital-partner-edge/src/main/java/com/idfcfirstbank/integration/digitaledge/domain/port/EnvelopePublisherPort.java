package com.idfcfirstbank.integration.digitaledge.domain.port;

import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;

/**
 * OUT port for publishing the canonical envelope to the SAME origination topic
 * the engine consumes. Throws on failure so the caller can classify it as
 * transient (do NOT ACK; the partner retries).
 */
public interface EnvelopePublisherPort {
    void publish(CanonicalEnvelope envelope, String topic);
}

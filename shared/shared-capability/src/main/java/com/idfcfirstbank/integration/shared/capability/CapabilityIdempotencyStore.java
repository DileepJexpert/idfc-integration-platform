package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;

import java.util.function.Supplier;

/**
 * Exactly-once execution gate for a capability request (BRD §2: idempotent on
 * (runId,nodeId)). {@link #executeOnce} runs {@code compute} AT MOST ONCE per
 * key — concurrent or redelivered requests with the same key get the SAME
 * response and never re-execute. Implementations MUST be atomic
 * (compute-if-absent / CREATE_ONLY CAS), not read-then-write.
 */
public interface CapabilityIdempotencyStore {

    CapabilityResponse executeOnce(String key, Supplier<CapabilityResponse> compute);
}

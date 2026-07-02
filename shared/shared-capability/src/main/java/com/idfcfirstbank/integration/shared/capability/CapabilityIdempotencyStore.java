package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;

import java.util.function.Supplier;

/**
 * Exactly-once execution gate for a capability request (BRD §2: idempotent on
 * (runId,nodeId)). {@link #executeOnce} runs {@code compute} AT MOST ONCE per
 * key for concurrent callers — they get the SAME response and never re-execute.
 * Implementations MUST be atomic (single-flight / CREATE_ONLY CAS), not
 * read-then-write.
 *
 * <p>Only SUCCESSFUL results are retained: a failed compute (ERROR response) is
 * NOT cached, so a later redelivery re-attempts it — this is what makes the
 * {@code TRANSIENT} error class retryable rather than pinning a one-off failure.
 */
public interface CapabilityIdempotencyStore {

    CapabilityResponse executeOnce(String key, Supplier<CapabilityResponse> compute);
}

package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;

import java.util.Optional;

/**
 * OUT port for money-movement idempotency. Keyed by the caller-supplied
 * {@code idempotentId}: a repeat of the same transfer must return the PRIOR result
 * and NOT re-execute the movement. Only DEFINITIVE outcomes are stored (a success
 * or a business decline); a technical failure is deliberately NOT stored, so the
 * caller may retry an ambiguous transfer (the backend's own idempotency on the
 * same {@code idempotentId} is the second line of defence).
 *
 * <p>The dev/test impl is in-memory; the production impl is the shared Aerospike
 * store (a host-config swap), exactly as the async lane dedupes partner resends.
 */
public interface ImpsIdempotencyStorePort {

    /** The prior definitive result for this idempotency key, if one was recorded. */
    Optional<ImpsFtResult> find(String idempotentId);

    /** Record a DEFINITIVE result (success or business decline) under the key. */
    void save(String idempotentId, ImpsFtResult result);
}

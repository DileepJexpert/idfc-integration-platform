package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out;

import java.util.Map;
import java.util.Optional;

/**
 * OUT port for SFDC identity-WRITE idempotency (user create / update, role assign).
 * Keyed by the caller-supplied key (namespaced by svcName+org so a key is scoped to its
 * operation): a repeat of the same write must return the PRIOR result and NOT re-execute
 * the mutation.
 *
 * <p>Only DEFINITIVE outcomes are stored — a 2xx that is either a success OR a business
 * rejection (e.g. duplicate username). A TECHNICAL failure (5xx / timeout / connect) is
 * deliberately NOT stored, so the caller may safely retry an ambiguous write under the
 * SAME key (this store, and SFDC's own idempotency, are the lines of defence). Same
 * discipline as the imps-disbursal money-movement store.
 *
 * <p>The dev/test impl is in-memory; the production impl is the shared Aerospike store
 * (a host-config swap), exactly as the async lane and imps dedupe.
 */
public interface SfdcIdempotencyStorePort {

    /** The prior definitive response for this key, if one was recorded. */
    Optional<Map<String, Object>> find(String key);

    /** Record a DEFINITIVE response (success or business rejection) under the key. */
    void save(String key, Map<String, Object> response);
}

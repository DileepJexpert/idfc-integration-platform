package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

import com.idfcfirstbank.integration.capabilities.mandate.domain.model.MandateTransaction;

import java.util.Optional;

/**
 * The mandate state store (this capability owns its state). {@link #insertIfAbsent}
 * is the atomic dedup gate on {@code invoiceNo} — exactly one concurrent register
 * wins and calls the vendor; the rest are idempotent no-ops. In-memory default; a
 * durable Aerospike CREATE_ONLY variant swaps in behind this port.
 */
public interface MandateStorePort {
    boolean insertIfAbsent(MandateTransaction txn);
    Optional<MandateTransaction> find(String invoiceNo);
    void save(MandateTransaction txn);
}

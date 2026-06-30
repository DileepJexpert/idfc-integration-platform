package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.store;

import com.idfcfirstbank.integration.capabilities.mandate.domain.model.MandateTransaction;
import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.MandateStorePort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory mandate store (demo default). {@code putIfAbsent} is the atomic
 * dedup gate on invoiceNo; a durable Aerospike CREATE_ONLY variant swaps in
 * behind {@link MandateStorePort} for multi-instance. */
@Component
public class InMemoryMandateStore implements MandateStorePort {

    private final ConcurrentHashMap<String, MandateTransaction> byInvoice = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(MandateTransaction txn) {
        return byInvoice.putIfAbsent(txn.invoiceNo(), txn) == null;
    }

    @Override
    public Optional<MandateTransaction> find(String invoiceNo) {
        return Optional.ofNullable(byInvoice.get(invoiceNo));
    }

    @Override
    public void save(MandateTransaction txn) {
        byInvoice.put(txn.invoiceNo(), txn);
    }
}

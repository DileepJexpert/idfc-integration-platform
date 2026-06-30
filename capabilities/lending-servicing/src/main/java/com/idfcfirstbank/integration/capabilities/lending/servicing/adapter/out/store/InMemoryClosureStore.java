package com.idfcfirstbank.integration.capabilities.lending.servicing.adapter.out.store;

import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model.ClosureRecord;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.ClosureStorePort;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/** In-memory closure store; putIfAbsent dedups on LAN+event. */
@Component
public class InMemoryClosureStore implements ClosureStorePort {

    private final ConcurrentHashMap<String, ClosureRecord> byKey = new ConcurrentHashMap<>();

    @Override
    public boolean insertIfAbsent(ClosureRecord record) {
        return byKey.putIfAbsent(record.lan() + ":" + record.event(), record) == null;
    }

    @Override
    public void save(ClosureRecord record) {
        byKey.put(record.lan() + ":" + record.event(), record);
    }
}

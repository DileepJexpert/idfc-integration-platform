package com.idfcfirstbank.integration.capabilities.communications.adapter.out.store;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SentSmsStorePort;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory send-idempotency (Docker-free). Durable Aerospike variant is a later slice. */
@Component
public class InMemorySentSmsStore implements SentSmsStorePort {

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Override
    public boolean markSentIfAbsent(String reference) {
        return sent.add(reference);   // false if already present (already sent)
    }
}

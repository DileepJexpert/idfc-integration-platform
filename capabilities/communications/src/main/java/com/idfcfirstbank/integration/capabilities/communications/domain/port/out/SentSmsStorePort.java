package com.idfcfirstbank.integration.capabilities.communications.domain.port.out;

/** Idempotency for sends: marks a reference as sent, returns false if already sent. */
public interface SentSmsStorePort {
    boolean markSentIfAbsent(String reference);
}

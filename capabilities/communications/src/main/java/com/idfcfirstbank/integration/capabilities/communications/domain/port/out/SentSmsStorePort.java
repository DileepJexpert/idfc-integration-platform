package com.idfcfirstbank.integration.capabilities.communications.domain.port.out;

/** Idempotency for sends: marks a reference as sent, returns false if already sent. */
public interface SentSmsStorePort {
    boolean markSentIfAbsent(String reference);

    /**
     * Release a claim made by {@link #markSentIfAbsent} when the send did NOT
     * succeed, so a redelivery can re-attempt it. Without this, a failed send after
     * an optimistic mark would suppress the OTP forever.
     */
    void unmark(String reference);
}

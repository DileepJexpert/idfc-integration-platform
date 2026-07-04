package com.idfcfirstbank.integration.edges.filebatch;

/** Port so the poller is unit-testable without a broker. */
public interface EnvelopePublisher {

    /** Publish one canonical-envelope JSON, keyed for partition affinity. */
    void publish(String topic, String key, String envelopeJson);
}

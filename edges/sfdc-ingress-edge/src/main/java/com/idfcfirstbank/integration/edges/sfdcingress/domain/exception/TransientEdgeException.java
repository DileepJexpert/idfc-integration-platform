package com.idfcfirstbank.integration.edges.sfdcingress.domain.exception;

/**
 * Possibly-transient failure (Kafka publish failure, store unavailable,
 * config-not-yet-loaded, timeout). C2: do NOT ACK — let SFDC redeliver. The C5
 * poison breaker bounds how many times this may repeat for one dedup key.
 */
public class TransientEdgeException extends EdgeException {
    public TransientEdgeException(String message, Throwable cause) {
        super(message, cause, false);
    }

    public TransientEdgeException(String message) {
        super(message, false);
    }
}

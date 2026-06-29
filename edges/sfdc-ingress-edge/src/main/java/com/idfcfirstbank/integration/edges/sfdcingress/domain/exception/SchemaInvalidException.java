package com.idfcfirstbank.integration.edges.sfdcingress.domain.exception;

/** Malformed / schema-invalid inbound. Provably permanent (C2: ACK + DLQ). */
public class SchemaInvalidException extends EdgeException {
    public SchemaInvalidException(String message) {
        super(message, true);
    }
}

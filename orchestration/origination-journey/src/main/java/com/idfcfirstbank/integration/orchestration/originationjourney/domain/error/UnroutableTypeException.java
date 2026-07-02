package com.idfcfirstbank.integration.orchestration.originationjourney.domain.error;

/**
 * An inbound origination {@code type} the engine has NO route for — either no
 * {@code type-to-journey} row, or the row points at a journey that is not
 * published/loaded. FAIL CLOSED: before A2 an unmapped type silently ran the
 * default (first-loaded) journey, so an account-creation event executed the loan
 * origination DAG. Retrying cannot fix a missing mapping, so the Kafka adapter
 * classifies this as poison (straight to the DLQ, where it is auditable).
 */
public class UnroutableTypeException extends RuntimeException {

    public UnroutableTypeException(String message) {
        super(message);
    }
}

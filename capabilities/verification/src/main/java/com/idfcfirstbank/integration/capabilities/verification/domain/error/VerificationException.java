package com.idfcfirstbank.integration.capabilities.verification.domain.error;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

/**
 * A verification failure classified for retry/DLQ. TRANSIENT is retried (2x200ms);
 * PERMANENT (or exhausted retries) routes to the DLQ — never a silent ack.
 * {@code errorCode} feeds the universal envelope's ERROR block.
 */
public class VerificationException extends RuntimeException {

    private final ErrorClass errorClass;
    private final String errorCode;

    public VerificationException(ErrorClass errorClass, String errorCode, String message) {
        super(message);
        this.errorClass = errorClass;
        this.errorCode = errorCode;
    }

    public ErrorClass errorClass() { return errorClass; }
    public String errorCode() { return errorCode; }
}

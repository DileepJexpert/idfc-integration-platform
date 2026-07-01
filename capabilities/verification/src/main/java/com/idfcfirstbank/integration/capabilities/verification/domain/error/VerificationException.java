package com.idfcfirstbank.integration.capabilities.verification.domain.error;

import com.idfcfirstbank.integration.shared.domain.capability.Classified;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

/**
 * A verification TRANSPORT failure classified for the retry-policy engine (spec v2 §C):
 * TRANSIENT is retried (exp backoff + jitter), AMBIGUOUS is retried only for idempotent
 * ops, PERMANENT (or exhausted retries) routes to DLQ + notifySfdc — never a silent ack.
 * Business declines are NOT this: they are HTTP-200 bodies handled by the branch.
 * {@code errorCode} feeds the universal envelope's ERROR block.
 */
public class VerificationException extends RuntimeException implements Classified {

    private final ErrorClass errorClass;
    private final String errorCode;

    public VerificationException(ErrorClass errorClass, String errorCode, String message) {
        super(message);
        this.errorClass = errorClass;
        this.errorCode = errorCode;
    }

    @Override public ErrorClass errorClass() { return errorClass; }
    public String errorCode() { return errorCode; }
}

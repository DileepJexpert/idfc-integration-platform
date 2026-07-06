package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.error;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

/**
 * A TECHNICAL failure on the sync lane — the downstream timed out, returned 5xx,
 * was unreachable, or otherwise could not be completed. It is NOT a business "no"
 * (those come back as a normal {@code ImpsFtResult} with a non-success status).
 * The controller turns this into a uniform 5xx to the caller (+ an internal
 * alert) — never a fake success. Carries an {@link ErrorClass} so the caller/ops
 * can tell a definitely-not-processed (PERMANENT) apart from a maybe-processed
 * (AMBIGUOUS, e.g. a read timeout on a money movement) or a retryable (TRANSIENT).
 */
public class SyncTechnicalException extends RuntimeException {

    private final ErrorClass errorClass;
    private final String code;

    public SyncTechnicalException(ErrorClass errorClass, String code, String message) {
        super(message);
        this.errorClass = errorClass;
        this.code = code;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }

    public String code() {
        return code;
    }
}

package com.idfcfirstbank.integration.shared.sync;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

/**
 * A TECHNICAL failure on the sync lane — the downstream timed out, returned 5xx,
 * was unreachable, or the request could not be dispatched. It is NOT a business
 * "no" (those come back as a normal result body). The sync ingress turns this into
 * a uniform 5xx to the caller (+ an internal alert) — never a fake success. The
 * {@link ErrorClass} lets the caller/ops tell a definitely-not-processed
 * (PERMANENT) apart from a maybe-processed (AMBIGUOUS, e.g. a read timeout on a
 * money movement) or a retryable (TRANSIENT).
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

package com.idfcfirstbank.integration.shared.capability;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;

/**
 * Thrown by a {@link CapabilityOperation} to signal a classified failure. The
 * dispatcher turns it into an ERROR {@code CapabilityResponse} carrying the
 * {@link ErrorClass} so the engine's retry policy (T2) can decide to re-dispatch
 * (TRANSIENT) or not (PERMANENT).
 */
public class CapabilityException extends RuntimeException {

    private final ErrorClass errorClass;

    public CapabilityException(ErrorClass errorClass, String message) {
        super(message);
        this.errorClass = errorClass;
    }

    public CapabilityException(ErrorClass errorClass, String message, Throwable cause) {
        super(message, cause);
        this.errorClass = errorClass;
    }

    public ErrorClass errorClass() {
        return errorClass;
    }
}

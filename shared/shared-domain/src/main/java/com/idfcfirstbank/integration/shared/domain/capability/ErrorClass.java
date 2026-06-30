package com.idfcfirstbank.integration.shared.domain.capability;

/**
 * Classifies a capability failure so the engine's retry policy can decide whether
 * to re-dispatch (BRD §2 result {@code errorClass}). TRANSIENT = retryable
 * (timeout, 5xx, hot key); PERMANENT = do not retry (validation, business reject).
 * Carried on {@link CapabilityResponse}; the engine acts on it in tier T2.
 */
public enum ErrorClass {
    TRANSIENT,
    PERMANENT
}

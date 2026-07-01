package com.idfcfirstbank.integration.shared.domain.capability;

/**
 * A failure that carries its transport-level {@link ErrorClass}, so the retry-policy
 * engine can classify it without instanceof-ing concrete exception types. Implemented
 * by capability exceptions (verification, mandate, ...). An exception that is NOT
 * Classified is treated as PERMANENT (do not blind-retry an unknown failure).
 */
public interface Classified {
    ErrorClass errorClass();
}

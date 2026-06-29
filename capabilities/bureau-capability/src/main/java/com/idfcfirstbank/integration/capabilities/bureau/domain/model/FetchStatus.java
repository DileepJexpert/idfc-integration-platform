package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * Overall outcome of a fetch across the requested bureauTypes.
 * PARTIAL = at least one bureau returned and at least one failed.
 */
public enum FetchStatus {
    SUCCESS,
    PARTIAL,
    FAILED
}

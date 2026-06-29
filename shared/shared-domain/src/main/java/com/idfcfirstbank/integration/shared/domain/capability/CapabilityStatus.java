package com.idfcfirstbank.integration.shared.domain.capability;

/**
 * Terminal outcome of a single capability invocation. Part of THE CAPABILITY
 * CONTRACT (see {@link CapabilityResponse}) — every capability sets this.
 */
public enum CapabilityStatus {
    /** The capability completed; {@link CapabilityResponse#result()} is populated. */
    OK,
    /** The capability failed; the engine treats the node as errored. */
    ERROR
}

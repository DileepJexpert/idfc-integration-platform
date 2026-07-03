package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

/** Lifecycle of a single journey run. */
public enum InstanceStatus {
    RUNNING,
    /**
     * T2 saga: the run has FAILED business-wise (the ERROR decision is already
     * on its way to the channel) and the engine is now dispatching the
     * compensation operations of completed compensable nodes, in reverse
     * completion order. Terminal FAILED follows once the saga finishes. Still
     * a LIVE state: the liveness sweeper's budget applies.
     */
    COMPENSATING,
    COMPLETED,
    FAILED;

    /** True for states a run can still make progress from. */
    public boolean isLive() {
        return this == RUNNING || this == COMPENSATING;
    }
}

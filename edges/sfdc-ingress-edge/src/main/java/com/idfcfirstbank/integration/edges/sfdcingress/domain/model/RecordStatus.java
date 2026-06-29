package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.util.Set;

/**
 * Lifecycle of an idempotency record (punch list §D):
 * {@code RECEIVED -> IN_FLIGHT -> DECIDED | FAILED}.
 * Every transition is a compare-and-set on the expected prior state (C4); this
 * enum is the single source of truth for which transitions are legal.
 */
public enum RecordStatus {
    RECEIVED,
    IN_FLIGHT,
    DECIDED,
    FAILED;

    private static final java.util.Map<RecordStatus, Set<RecordStatus>> ALLOWED = java.util.Map.of(
            RECEIVED, Set.of(IN_FLIGHT, FAILED, DECIDED),
            IN_FLIGHT, Set.of(DECIDED, FAILED),
            // FAILED -> IN_FLIGHT is the single C3 transient re-enqueue.
            FAILED, Set.of(IN_FLIGHT, RECEIVED),
            DECIDED, Set.of() // terminal
    );

    public boolean canTransitionTo(RecordStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }

    public boolean isTerminal() {
        return this == DECIDED;
    }
}

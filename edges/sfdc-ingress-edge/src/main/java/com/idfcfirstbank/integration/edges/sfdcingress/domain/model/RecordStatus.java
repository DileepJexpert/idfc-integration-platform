package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.util.Set;

/**
 * Lifecycle of an idempotency record (punch list §D):
 * {@code RECEIVED -> IN_FLIGHT -> PUBLISHED -> DECIDED | FAILED}.
 * Every transition is a compare-and-set on the expected prior state (C4); this
 * enum is the single source of truth for which transitions are legal.
 *
 * <p>{@code PUBLISHED} marks "the envelope is confirmed on Kafka" — the point up
 * to which a resend must RE-DRIVE the publish (a record stuck in RECEIVED, or in
 * IN_FLIGHT past the publish lease, is a crashed attempt, not a duplicate) and
 * past which a resend is a true idempotent no-op.
 */
public enum RecordStatus {
    RECEIVED,
    IN_FLIGHT,
    PUBLISHED,
    DECIDED,
    FAILED;

    private static final java.util.Map<RecordStatus, Set<RecordStatus>> ALLOWED = java.util.Map.of(
            RECEIVED, Set.of(IN_FLIGHT, FAILED, DECIDED),
            IN_FLIGHT, Set.of(PUBLISHED, DECIDED, FAILED),
            PUBLISHED, Set.of(DECIDED, FAILED),
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

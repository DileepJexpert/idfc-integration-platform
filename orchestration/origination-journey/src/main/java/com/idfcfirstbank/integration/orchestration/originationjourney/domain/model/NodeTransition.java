package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.time.Instant;

/**
 * One per-node lifecycle transition of a run (ops timeline, B.1): which node,
 * which state, when. List ORDER is the event sequence — consumers must order by
 * position, not wall-clock (clock skew, D11). {@code late} marks a transition
 * recorded after the run already ended (at-least-once redelivery, D10): kept
 * for the audit trail, flagged so the UI never visually "reopens" a run.
 *
 * <p>Ids only — a transition NEVER carries payload or error prose (PII, D13).
 */
public record NodeTransition(String nodeId, Status status, Instant at, boolean late) {

    public enum Status { DISPATCHED, COMPLETED, FAILED }
}

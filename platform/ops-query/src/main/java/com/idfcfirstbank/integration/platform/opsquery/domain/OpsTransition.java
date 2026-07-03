package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.time.Instant;

/**
 * One row of a run's node timeline. {@code seq} is the EVENT-SEQUENCE position —
 * clients order by it, never by wall-clock alone (clock skew, D11). {@code late}
 * flags a transition recorded after the run ended (D10): shown, never treated as
 * reopening the run.
 */
public record OpsTransition(int seq, String nodeId, String status, Instant at, boolean late) {
}

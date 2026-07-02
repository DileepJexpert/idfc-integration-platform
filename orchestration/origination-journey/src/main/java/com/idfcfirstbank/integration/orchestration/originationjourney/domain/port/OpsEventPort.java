package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.OpsEvent;

/**
 * OUT port: run-lifecycle events for the observability stack (B.2).
 *
 * <p>CONTRACT: {@code emit} never throws and never blocks the business hop —
 * ops events are confirmed at the adapter (so a broker failure is KNOWN, logged
 * and counted) but a run must never fail because observability is down. The
 * ops read-API reads the instance store directly and does not depend on these
 * events; consumers treat them as at-least-once.
 */
public interface OpsEventPort {

    void emit(OpsEvent event);

    /** For tests and non-Kafka wiring: swallow events. */
    OpsEventPort NOOP = event -> { };
}

package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RecordStatus;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.CasResult;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.SfdcResponsePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Applies a downstream decision to a record and pushes it back to SFDC.
 *
 * <p><b>C1 ownership guard (LOCKED):</b> the push-back fires EXCLUSIVELY on the
 * successful CAS transition INTO {@code DECIDED}. If the CAS fails because the
 * record is already DECIDED (a concurrent or duplicate decision), this method
 * does NOT push again — the SFDC record is the idempotent sink and is already
 * updated. Resend reads elsewhere never call this. This removes the
 * in-flight→decided race ambiguity: the push fires exactly once, never zero or
 * twice.
 *
 * <p>In Slice 1 the downstream journey is mocked, so this is driven by tests /
 * a decision-callback adapter; the wiring is identical when the real journey
 * arrives.
 */
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final IdempotencyStorePort store;
    private final SfdcResponsePort sfdcResponse;

    public DecisionService(IdempotencyStorePort store, SfdcResponsePort sfdcResponse) {
        this.store = store;
        this.sfdcResponse = sfdcResponse;
    }

    /**
     * @param notificationId the record to decide
     * @param decision       the downstream decision (opaque to the edge)
     * @param correlationId  trace of the call that delivered the decision; threaded into the push-back
     * @return true if THIS call performed the transition and pushed; false if it was already decided
     */
    public boolean applyDecision(String notificationId, Decision decision, String correlationId) {
        Optional<IdempotencyRecord> found = store.findByNotificationId(notificationId);
        if (found.isEmpty()) {
            log.warn("decision.unknown-record notificationId={}", notificationId);
            return false;
        }
        IdempotencyRecord record = found.get();
        if (record.status() == RecordStatus.DECIDED) {
            // Already decided — idempotent sink is updated; do NOT push again (C1).
            log.info("decision.already-decided notificationId={} (no push)", notificationId);
            return false;
        }

        CasResult cas = store.compareAndSetStatus(record, RecordStatus.DECIDED, decision);
        if (!cas.applied()) {
            // Lost the race INTO DECIDED — the winner pushed; we must not.
            log.info("decision.cas-lost notificationId={} (no push)", notificationId);
            return false;
        }

        // We own the transition INTO DECIDED — push exactly once.
        sfdcResponse.pushDecision(cas.record(), correlationId);
        log.info("decision.pushed notificationId={} outcome={} correlationId={}",
                notificationId, decision.outcome(), correlationId);
        return true;
    }
}

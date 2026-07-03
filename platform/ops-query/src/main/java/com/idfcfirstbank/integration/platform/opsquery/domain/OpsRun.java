package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.time.Instant;
import java.util.List;

/**
 * The ops module's OWN read model of one journey run — populated by the host
 * app's {@link OpsRunStore} adapter. Deliberately id-shaped: there is no field
 * a payload could travel in (PII rule D13 enforced by the type system, proven
 * by the no-payload DTO test).
 */
public record OpsRun(
        String runId,
        String journeyKey,
        int journeyVersion,
        State state,
        String outcome,          // APPROVED / REJECTED / ERROR once terminal
        Notify sfdcNotified,
        Instant startedAt,
        Instant endedAt,
        String terminalNodeId,
        String correlationId,
        String notificationId,
        String sfdcRecordId,
        List<OpsTransition> transitions) {

    public OpsRun {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    /** Raw store state of a run. */
    public enum State { RUNNING, COMPLETED, FAILED }

    /** Has the channel (SFDC/partner) been told the outcome? */
    public enum Notify { NONE, PENDING, SENT }

    /**
     * The BANK-CORRECT status vocabulary (C.4): a business decline via the
     * decline branch is a NORMAL COMPLETION, never a failure — a declined loan
     * shown red pages someone at 2am for a correctly-rejected application. A
     * §7 "failed" terminal (outcome ERROR) IS a failure even though the run
     * ended in an orderly way. FAILED splits on the single most triage-relevant
     * bit: was the channel told (the agent re-sends) or not (nobody will).
     */
    public enum StatusVocabulary {
        RUNNING, COMPLETED_APPROVED, COMPLETED_DECLINED, FAILED_SFDC_NOTIFIED, FAILED_NOTIFY_PENDING
    }

    public StatusVocabulary status() {
        if (state == State.RUNNING) {
            return StatusVocabulary.RUNNING;
        }
        if (state == State.COMPLETED && "APPROVED".equals(outcome)) {
            return StatusVocabulary.COMPLETED_APPROVED;
        }
        if (state == State.COMPLETED && "REJECTED".equals(outcome)) {
            return StatusVocabulary.COMPLETED_DECLINED;
        }
        // FAILED state, or a completed run whose terminal declared ERROR.
        return sfdcNotified == Notify.SENT
                ? StatusVocabulary.FAILED_SFDC_NOTIFIED
                : StatusVocabulary.FAILED_NOTIFY_PENDING;
    }
}

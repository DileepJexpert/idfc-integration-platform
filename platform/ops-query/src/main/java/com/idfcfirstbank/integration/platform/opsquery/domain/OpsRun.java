package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        List<OpsTransition> transitions,
        // OPS P2 (all id-shaped): dispatch attempts per node, failure-class
        // ENUM NAMES per terminally-failed node, and the compensation saga's
        // terminal node + remaining queue.
        Map<String, Integer> dispatchAttempts,
        Map<String, String> nodeFailureClasses,
        String compensationOf,
        List<String> compensationPending) {

    public OpsRun {
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        dispatchAttempts = dispatchAttempts == null ? Map.of() : Map.copyOf(dispatchAttempts);
        nodeFailureClasses = nodeFailureClasses == null ? Map.of() : Map.copyOf(nodeFailureClasses);
        compensationPending = compensationPending == null ? List.of() : List.copyOf(compensationPending);
    }

    /** Pre-P2 form — existing builders/tests keep working; P2 fields empty. */
    public OpsRun(String runId, String journeyKey, int journeyVersion, State state, String outcome,
                  Notify sfdcNotified, Instant startedAt, Instant endedAt, String terminalNodeId,
                  String correlationId, String notificationId, String sfdcRecordId,
                  List<OpsTransition> transitions) {
        this(runId, journeyKey, journeyVersion, state, outcome, sfdcNotified, startedAt, endedAt,
                terminalNodeId, correlationId, notificationId, sfdcRecordId, transitions,
                Map.of(), Map.of(), null, List.of());
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

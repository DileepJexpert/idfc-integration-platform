package com.idfcfirstbank.integration.platform.opsquery;

import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsTransition;

import java.time.Instant;
import java.util.List;

/** Compact OpsRun builders for the module tests. */
public final class FixtureRuns {

    private FixtureRuns() {
    }

    public static OpsRun running(String runId, Instant startedAt) {
        return new OpsRun(runId, "loan-origination", 1, OpsRun.State.RUNNING, null,
                OpsRun.Notify.NONE, startedAt, null, null,
                "corr-" + runId, "ntf-" + runId, "rec-" + runId, List.of());
    }

    public static OpsRun completed(String runId, Instant startedAt, String outcome) {
        return new OpsRun(runId, "loan-origination", 1, OpsRun.State.COMPLETED, outcome,
                OpsRun.Notify.SENT, startedAt, startedAt.plusSeconds(60), "n_done",
                "corr-" + runId, "ntf-" + runId, "rec-" + runId,
                List.of(new OpsTransition(0, "n_verify", "DISPATCHED", startedAt, false),
                        new OpsTransition(1, "n_verify", "COMPLETED", startedAt.plusSeconds(30), false)));
    }

    public static OpsRun failed(String runId, Instant startedAt, OpsRun.Notify notify) {
        return new OpsRun(runId, "loan-origination", 1, OpsRun.State.FAILED, "ERROR",
                notify, startedAt, startedAt.plusSeconds(60), "n_book",
                "corr-" + runId, "ntf-" + runId, "rec-" + runId, List.of());
    }

    public static OpsRun withSfdcRecord(OpsRun run, String sfdcRecordId) {
        return new OpsRun(run.runId(), run.journeyKey(), run.journeyVersion(), run.state(),
                run.outcome(), run.sfdcNotified(), run.startedAt(), run.endedAt(),
                run.terminalNodeId(), run.correlationId(), run.notificationId(),
                sfdcRecordId, run.transitions());
    }
}

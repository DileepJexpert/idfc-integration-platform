package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.ops;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeTransition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunStore;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsTransition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adapts the engine's run store to the ops module's read port. THE PII GATE
 * (D13) is this mapping: only ids/timestamps/enum names cross — the payload
 * map, collected results and context NEVER appear on the right-hand side, and
 * the ops DTO tree has no field they could ride in anyway. notificationId /
 * sfdcRecordId are ids the envelope carried; they are extracted individually,
 * never by passing the map.
 */
public class OpsRunStoreAdapter implements OpsRunStore {

    private final JourneyInstanceStore store;

    public OpsRunStoreAdapter(JourneyInstanceStore store) {
        this.store = store;
    }

    @Override
    public Optional<OpsRun> find(String runId) {
        return store.find(runId).map(OpsRunStoreAdapter::toOpsRun);
    }

    @Override
    public List<OpsRun> scanAll() {
        return store.scanAll().stream().map(OpsRunStoreAdapter::toOpsRun).toList();
    }

    private static OpsRun toOpsRun(JourneyInstance i) {
        return new OpsRun(
                i.journeyInstanceId(),
                i.journeyKey(),
                i.journeyVersion(),
                stateOf(i.status()),
                i.terminalOutcome(),
                notifyOf(i.sfdcNotified()),
                i.startedAt(),
                i.endedAt(),
                i.terminalNodeId(),
                i.correlationId(),
                idFromPayload(i, "notificationId"),
                idFromPayload(i, "sfdcRecordId"),
                transitionsOf(i.transitions()));
    }

    private static OpsRun.State stateOf(InstanceStatus status) {
        return switch (status) {
            case RUNNING -> OpsRun.State.RUNNING;
            case COMPLETED -> OpsRun.State.COMPLETED;
            case FAILED -> OpsRun.State.FAILED;
        };
    }

    private static OpsRun.Notify notifyOf(JourneyInstance.NotifyState state) {
        return switch (state) {
            case NONE -> OpsRun.Notify.NONE;
            case PENDING -> OpsRun.Notify.PENDING;
            case SENT -> OpsRun.Notify.SENT;
        };
    }

    private static List<OpsTransition> transitionsOf(List<NodeTransition> transitions) {
        List<OpsTransition> out = new ArrayList<>(transitions.size());
        for (int seq = 0; seq < transitions.size(); seq++) {
            NodeTransition t = transitions.get(seq);
            out.add(new OpsTransition(seq, t.nodeId(), t.status().name(), t.at(), t.late()));
        }
        return out;
    }

    /** Individual id extraction — the payload MAP itself never crosses this seam. */
    private static String idFromPayload(JourneyInstance i, String key) {
        Object v = i.payload().get(key);
        return v == null ? null : String.valueOf(v);
    }
}

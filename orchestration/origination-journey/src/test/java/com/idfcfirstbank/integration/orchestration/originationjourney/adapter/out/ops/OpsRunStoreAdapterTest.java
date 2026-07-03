package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.ops;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine->ops mapping IS the PII gate (D13): ids are extracted one by one,
 * the payload map never crosses, and the transitions carry sequence numbers.
 */
class OpsRunStoreAdapterTest {

    @Test
    void mapsIdsStateAndTimelineNeverThePayload() {
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        JourneyInstance i = new JourneyInstance("ji-map-1", "corr-map", "loan-origination", 3,
                "APP-9", Map.of(
                        "notificationId", "NTF-9", "sfdcRecordId", "REC-9",
                        "pan", "ABCDE1234F"));   // PII in the payload — must NOT cross
        store.insertIfAbsent(i);
        i.markDispatched("n_kyc");
        i.recordResult("n_kyc", "kyc", "context.kyc", Map.of("ok", true));
        i.complete("n_done", JourneyDecision.APPROVED);
        store.save(i);

        OpsRunStoreAdapter adapter = new OpsRunStoreAdapter(store);
        OpsRun run = adapter.find("ji-map-1").orElseThrow();

        assertThat(run.runId()).isEqualTo("ji-map-1");
        assertThat(run.journeyKey()).isEqualTo("loan-origination");
        assertThat(run.journeyVersion()).isEqualTo(3);
        assertThat(run.state()).isEqualTo(OpsRun.State.COMPLETED);
        assertThat(run.outcome()).isEqualTo("APPROVED");
        assertThat(run.status()).isEqualTo(OpsRun.StatusVocabulary.COMPLETED_APPROVED);
        assertThat(run.notificationId()).isEqualTo("NTF-9");
        assertThat(run.sfdcRecordId()).isEqualTo("REC-9");
        assertThat(run.terminalNodeId()).isEqualTo("n_done");
        assertThat(run.transitions()).hasSize(2);
        assertThat(run.transitions().get(0).seq()).isZero();
        assertThat(run.transitions().get(1).seq()).isEqualTo(1);
        assertThat(run.transitions().get(1).status()).isEqualTo("COMPLETED");
        assertThat(adapter.scanAll()).hasSize(1);
    }

    @Test
    void mapsTheP2Stats_attemptsClassesAndSagaState() {
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        JourneyInstance i = new JourneyInstance("ji-p2-1", "corr-p2", "loan-origination", 1,
                "APP-1", Map.of());
        store.insertIfAbsent(i);
        i.markDispatched("n_kyc");
        i.bumpAttempt("n_kyc");
        i.bumpAttempt("n_kyc");
        i.recordNodeFailure("n_kyc", "TRANSIENT");
        i.startCompensation("n_book", java.util.List.of("n_t2", "n_t1"));
        store.save(i);

        OpsRun run = new OpsRunStoreAdapter(store).find("ji-p2-1").orElseThrow();
        assertThat(run.dispatchAttempts()).containsEntry("n_kyc", 2);
        assertThat(run.nodeFailureClasses()).containsEntry("n_kyc", "TRANSIENT");
        assertThat(run.compensationOf()).isEqualTo("n_book");
        assertThat(run.compensationPending()).containsExactly("n_t2", "n_t1");
        // COMPENSATING remains in the RUNNING band (vocabulary is fixed).
        assertThat(run.state()).isEqualTo(OpsRun.State.RUNNING);
    }
}

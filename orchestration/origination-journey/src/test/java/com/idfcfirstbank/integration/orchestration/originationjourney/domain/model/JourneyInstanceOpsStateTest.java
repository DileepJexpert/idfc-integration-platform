package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B.1: the per-node transition history and terminal detail an ops timeline is
 * built from — idempotent under at-least-once redelivery (D10), bounded, late
 * transitions flagged (never visually reopening a run), and the sfdcNotified
 * lifecycle riding the existing pending-publish outbox.
 */
class JourneyInstanceOpsStateTest {

    private static JourneyInstance run() {
        return new JourneyInstance("ji-ops-1", "corr-ops", "loan-origination", 1, "APP-1",
                Map.of("notificationId", "N-1", "sfdcRecordId", "R-1"));
    }

    @Test
    void transitionAppendsAreIdempotentPerNodeAndStatus() {
        JourneyInstance i = run();

        i.markDispatched("n_kyc");
        i.markDispatched("n_kyc"); // redelivered hop — must NOT duplicate the row
        i.recordResult("n_kyc", "kyc", "context.kyc", Map.of("ok", true));
        i.recordResult("n_kyc", "kyc", "context.kyc", Map.of("ok", true));

        assertThat(i.transitions()).extracting(NodeTransition::nodeId, NodeTransition::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("n_kyc", NodeTransition.Status.DISPATCHED),
                        org.assertj.core.groups.Tuple.tuple("n_kyc", NodeTransition.Status.COMPLETED));
    }

    @Test
    void listOrderIsTheEventSequence() {
        JourneyInstance i = run();
        i.markDispatched("a");
        i.markDispatched("b");
        i.recordResult("a", "capA", null, Map.of());
        i.recordResult("b", "capB", null, Map.of());

        assertThat(i.transitions()).extracting(NodeTransition::nodeId)
                .containsExactly("a", "b", "a", "b");
    }

    @Test
    void aTransitionAfterTheRunEndedIsKeptButFlaggedLate() {
        JourneyInstance i = run();
        i.markDispatched("n_kyc");
        i.complete("n_done", JourneyDecision.APPROVED);

        i.recordResult("n_kyc", "kyc", null, Map.of()); // late response after terminal (D10)

        NodeTransition late = i.transitions().get(i.transitions().size() - 1);
        assertThat(late.nodeId()).isEqualTo("n_kyc");
        assertThat(late.status()).isEqualTo(NodeTransition.Status.COMPLETED);
        assertThat(late.late()).as("recorded, flagged, never reopens the run").isTrue();
        assertThat(i.status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void theTimelineIsBounded() {
        JourneyInstance i = run();
        for (int n = 0; n < 500; n++) {
            i.markDispatched("node_" + n);
        }
        assertThat(i.transitions()).hasSize(200);
    }

    @Test
    void terminalDetailAndEndedAtAreStampedOnceFirstTerminalWins() {
        JourneyInstance i = run();
        i.fail("n_book", JourneyDecision.ERROR);
        var endedAt = i.endedAt();

        i.complete("n_done", JourneyDecision.APPROVED); // recovery terminal after onFailure route

        assertThat(i.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(i.terminalNodeId()).isEqualTo("n_done");
        assertThat(i.terminalOutcome()).isEqualTo(JourneyDecision.APPROVED);
        assertThat(i.endedAt()).as("end time is the FIRST terminal event").isEqualTo(endedAt);
    }

    @Test
    void sfdcNotifiedRidesThePendingPublishOutbox() {
        JourneyInstance i = run();
        assertThat(i.sfdcNotified()).isEqualTo(JourneyInstance.NotifyState.NONE);

        // Node dispatches alone never touch the notify state.
        i.setPendingPublishes(List.of("n_kyc"), null);
        i.clearPendingPublishes();
        assertThat(i.sfdcNotified()).isEqualTo(JourneyInstance.NotifyState.NONE);

        // A decision exists but is not yet confirmed-published: PENDING.
        JourneyDecision decision = new JourneyDecision("ji-ops-1", "corr-ops", "APP-1",
                JourneyDecision.APPROVED, null, "n_done", List.of(), "SFDC", "N-1", "R-1");
        i.setPendingPublishes(List.of(), decision);
        assertThat(i.sfdcNotified()).isEqualTo(JourneyInstance.NotifyState.PENDING);

        // The confirmed publish clears the intent: SENT.
        i.clearPendingPublishes();
        assertThat(i.sfdcNotified()).isEqualTo(JourneyInstance.NotifyState.SENT);
    }

    @Test
    void theInMemoryStoreRoundTripsTheFullOpsState() {
        InMemoryJourneyInstanceStore store = new InMemoryJourneyInstanceStore();
        JourneyInstance original = run();
        store.insertIfAbsent(original);

        original.markDispatched("n_kyc");
        original.recordResult("n_kyc", "kyc", "context.kyc", Map.of("ok", true));
        original.setPendingPublishes(List.of(), new JourneyDecision("ji-ops-1", "corr-ops",
                "APP-1", JourneyDecision.REJECTED, null, "n_reject", List.of(), "SFDC", "N-1", "R-1"));
        original.clearPendingPublishes();
        original.complete("n_reject", JourneyDecision.REJECTED);
        store.save(original);

        JourneyInstance restored = store.find("ji-ops-1").orElseThrow();
        assertThat(restored.transitions()).hasSize(2);
        assertThat(restored.transitions().get(0).status()).isEqualTo(NodeTransition.Status.DISPATCHED);
        assertThat(restored.endedAt()).isNotNull();
        assertThat(restored.terminalNodeId()).isEqualTo("n_reject");
        assertThat(restored.terminalOutcome()).isEqualTo(JourneyDecision.REJECTED);
        assertThat(restored.sfdcNotified()).isEqualTo(JourneyInstance.NotifyState.SENT);
    }
}

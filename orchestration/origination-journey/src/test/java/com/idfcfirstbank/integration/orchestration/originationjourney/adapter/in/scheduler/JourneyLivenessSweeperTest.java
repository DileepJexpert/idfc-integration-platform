package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.scheduler;

import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store.InMemoryJourneyInstanceStore;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.DecisionOutboundPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for Phase 1 item 5: a RUNNING instance older than the run
 * budget is failed AND notified (so the assisted retry loop is not left hanging);
 * a fresh run and an already-terminal run are untouched; and if the notify cannot
 * be published the run stays RUNNING to be retried next sweep (never silently
 * dropped).
 */
class JourneyLivenessSweeperTest {

    private static final long BUDGET_SECONDS = 900; // 15 minutes
    private static final Instant NOW = Instant.parse("2026-07-02T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private JourneyInstance running(String id, String appRef, Duration age, Map<String, Object> payload) {
        return new JourneyInstance(id, "corr-" + id, "loan-origination", appRef, payload, NOW.minus(age));
    }

    @Test
    void failsAndNotifiesRunsPastBudgetOnly() {
        var store = new InMemoryJourneyInstanceStore();
        store.insertIfAbsent(running("ji-stale", "APP-1", Duration.ofMinutes(20),
                Map.of("source", "SFDC", "notificationId", "N1", "sfdcRecordId", "R1")));
        store.insertIfAbsent(running("ji-fresh", "APP-2", Duration.ofMinutes(1), Map.of()));
        JourneyInstance done = running("ji-done", "APP-3", Duration.ofMinutes(30), Map.of());
        done.complete();
        store.insertIfAbsent(done);

        List<JourneyDecision> published = new ArrayList<>();
        var sweeper = new JourneyLivenessSweeper(store, published::add, clock, BUDGET_SECONDS);

        int failed = sweeper.sweepStuckRuns();

        assertThat(failed).isEqualTo(1);
        assertThat(published).hasSize(1);
        JourneyDecision d = published.get(0);
        assertThat(d.journeyInstanceId()).isEqualTo("ji-stale");
        assertThat(d.outcome()).isEqualTo(JourneyDecision.ERROR);
        assertThat(d.terminalNodeId()).isEqualTo(JourneyLivenessSweeper.TIMEOUT_NODE_ID);
        assertThat(d.source()).isEqualTo("SFDC");
        assertThat(d.notificationId()).isEqualTo("N1");
        assertThat(d.sfdcRecordId()).isEqualTo("R1");

        assertThat(store.find("ji-stale").orElseThrow().status()).isEqualTo(InstanceStatus.FAILED);
        assertThat(store.find("ji-fresh").orElseThrow().status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(store.find("ji-done").orElseThrow().status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void leavesRunRunningWhenNotifyCannotBePublished() {
        var store = new InMemoryJourneyInstanceStore();
        store.insertIfAbsent(running("ji-stale", "APP-1", Duration.ofMinutes(20), Map.of()));

        DecisionOutboundPort throwingPort = d -> {
            throw new RuntimeException("broker down");
        };
        var sweeper = new JourneyLivenessSweeper(store, throwingPort, clock, BUDGET_SECONDS);

        int failed = sweeper.sweepStuckRuns();

        assertThat(failed).isZero();
        // Not marked FAILED: the next sweep retries so the agent is eventually told.
        assertThat(store.find("ji-stale").orElseThrow().status()).isEqualTo(InstanceStatus.RUNNING);
    }
}

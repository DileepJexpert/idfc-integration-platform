package com.idfcfirstbank.integration.platform.opsquery.domain;

import com.idfcfirstbank.integration.platform.opsquery.FixtureRuns;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp.SeedableOpsRunStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two rule sets everything else rides on: the stuckOnly BOUNDARY
 * ({@code budget − sweepInterval}, so a run surfaces BEFORE the sweeper fires,
 * D9) and the bank-correct status vocabulary (C.4 — a DECLINE is a completion,
 * a "failed" §7 terminal is a failure, FAILED splits on the notify bit).
 */
class OpsRunQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SeedableOpsRunStore store = new SeedableOpsRunStore();
    /** budget 900s, sweep 60s -> stuck threshold 840s. */
    private final OpsRunQueryService service = new OpsRunQueryService(
            store, CLOCK, Duration.ofSeconds(900), Duration.ofSeconds(60));

    @Test
    void stuckMeansApproachingTheBudgetNotAlreadySweptPast() {
        OpsRun atThreshold = FixtureRuns.running("ji-at", NOW.minusSeconds(840));
        OpsRun justUnder = FixtureRuns.running("ji-under", NOW.minusSeconds(839));
        OpsRun wellPast = FixtureRuns.running("ji-past", NOW.minusSeconds(2000));
        OpsRun terminalOld = FixtureRuns.completed("ji-done", NOW.minusSeconds(5000), "APPROVED");

        assertThat(service.isStuck(atThreshold))
                .as("AT budget − sweepInterval counts as stuck — the run surfaces"
                        + " one sweep BEFORE the sweeper would fail it")
                .isTrue();
        assertThat(service.isStuck(justUnder)).isFalse();
        assertThat(service.isStuck(wellPast)).isTrue();
        assertThat(service.isStuck(terminalOld))
                .as("terminal runs are never stuck")
                .isFalse();

        store.runs.addAll(List.of(atThreshold, justUnder, wellPast, terminalOld));
        assertThat(service.stuckCount()).isEqualTo(2);
        assertThat(service.list(new OpsRunQueryService.Filter(null, null, null, null, true), 0, 50)
                .items()).extracting(OpsRun::runId).containsExactlyInAnyOrder("ji-at", "ji-past");
    }

    @Test
    void aSweepIntervalLongerThanTheBudgetStillMeansApproachingBudget() {
        OpsRunQueryService weird = new OpsRunQueryService(
                store, CLOCK, Duration.ofSeconds(60), Duration.ofSeconds(900));
        assertThat(weird.isStuck(FixtureRuns.running("ji-x", NOW.minusSeconds(30)))).isTrue();
    }

    @Test
    void theStatusVocabularyIsBankCorrect() {
        assertThat(FixtureRuns.running("r", NOW).status())
                .isEqualTo(OpsRun.StatusVocabulary.RUNNING);
        assertThat(FixtureRuns.completed("a", NOW, "APPROVED").status())
                .isEqualTo(OpsRun.StatusVocabulary.COMPLETED_APPROVED);
        assertThat(FixtureRuns.completed("d", NOW, "REJECTED").status())
                .as("a business decline is a NORMAL COMPLETION, never a failure (C.4)")
                .isEqualTo(OpsRun.StatusVocabulary.COMPLETED_DECLINED);
        assertThat(FixtureRuns.completed("e", NOW, "ERROR").status())
                .as("a §7 'failed' terminal completes the run but IS a failure for ops")
                .isEqualTo(OpsRun.StatusVocabulary.FAILED_SFDC_NOTIFIED);
        assertThat(FixtureRuns.failed("fn", NOW, OpsRun.Notify.SENT).status())
                .isEqualTo(OpsRun.StatusVocabulary.FAILED_SFDC_NOTIFIED);
        assertThat(FixtureRuns.failed("fp", NOW, OpsRun.Notify.PENDING).status())
                .as("the agent will never re-send an un-notified failure — top of triage")
                .isEqualTo(OpsRun.StatusVocabulary.FAILED_NOTIFY_PENDING);
        assertThat(FixtureRuns.failed("f0", NOW, OpsRun.Notify.NONE).status())
                .isEqualTo(OpsRun.StatusVocabulary.FAILED_NOTIFY_PENDING);
    }

    @Test
    void searchMatchesAllFourIdFamiliesExactly() {
        store.runs.add(FixtureRuns.running("ji-s1", NOW.minusSeconds(10)));
        store.runs.add(FixtureRuns.withSfdcRecord(
                FixtureRuns.completed("ji-s2", NOW.minusSeconds(300), "APPROVED"), "REC-SHARED"));
        store.runs.add(FixtureRuns.withSfdcRecord(
                FixtureRuns.failed("ji-s3", NOW.minusSeconds(600), OpsRun.Notify.SENT), "REC-SHARED"));

        assertThat(service.search("ji-s1")).extracting(OpsRun::runId).containsExactly("ji-s1");
        assertThat(service.search("corr-ji-s1")).extracting(OpsRun::runId).containsExactly("ji-s1");
        assertThat(service.search("ntf-ji-s2")).extracting(OpsRun::runId).containsExactly("ji-s2");
        assertThat(service.search("REC-SHARED"))
                .as("a re-sent business record has SEVERAL runs — return them all,"
                        + " newest first (D12)")
                .extracting(OpsRun::runId).containsExactly("ji-s2", "ji-s3");
        assertThat(service.search("corr")).as("EXACT match only, no substrings").isEmpty();
    }

    @Test
    void metricsAggregatePerJourneyWithStatusCountsSortingAndDurationPercentiles() {
        Instant t = NOW.minusSeconds(3600);
        // journey "alpha": 5 approved (10/20/30/40/100s), 1 failed (5s), 1 running.
        int[] secs = {10, 20, 30, 40, 100};
        for (int i = 0; i < secs.length; i++) {
            store.runs.add(run("alpha", "a" + i, OpsRun.State.COMPLETED, "APPROVED",
                    t, t.plusSeconds(secs[i]), OpsRun.Notify.SENT));
        }
        store.runs.add(run("alpha", "afail", OpsRun.State.FAILED, "ERROR",
                t, t.plusSeconds(5), OpsRun.Notify.PENDING));
        store.runs.add(run("alpha", "arun", OpsRun.State.RUNNING, null,
                NOW.minusSeconds(30), null, OpsRun.Notify.NONE));
        // journey "beta": 1 declined (15s).
        store.runs.add(run("beta", "b0", OpsRun.State.COMPLETED, "REJECTED",
                t, t.plusSeconds(15), OpsRun.Notify.SENT));

        OpsRunQueryService.MetricsSnapshot snap = service.metrics();

        assertThat(snap.generatedAt()).isEqualTo(NOW);
        assertThat(snap.journeys()).extracting(OpsRunQueryService.JourneyMetrics::journeyKey)
                .as("sorted by total desc: alpha(7) before beta(1)")
                .containsExactly("alpha", "beta");

        OpsRunQueryService.JourneyMetrics alpha = snap.journeys().get(0);
        assertThat(alpha.total()).isEqualTo(7);
        assertThat(alpha.completedApproved()).isEqualTo(5);
        assertThat(alpha.completedDeclined()).isZero();
        assertThat(alpha.failed()).isEqualTo(1);
        assertThat(alpha.running()).isEqualTo(1);
        assertThat(alpha.stuck())
                .as("the lone running run started 30s ago — nowhere near the budget").isZero();
        assertThat(alpha.startedLast24h()).isEqualTo(7);
        // ended durations ascending (ms): 5000,10000,20000,30000,40000,100000 (running excluded).
        assertThat(alpha.p50Millis()).isEqualTo(20_000L);  // nearest-rank p50 of 6 -> idx 2
        assertThat(alpha.p95Millis()).isEqualTo(100_000L); // idx 5

        OpsRunQueryService.JourneyMetrics beta = snap.journeys().get(1);
        assertThat(beta.total()).isEqualTo(1);
        assertThat(beta.completedDeclined()).isEqualTo(1);
        assertThat(beta.p50Millis()).isEqualTo(15_000L);
        assertThat(beta.p95Millis()).isEqualTo(15_000L);
    }

    @Test
    void metricsAreEmptyWhenThereAreNoRuns() {
        assertThat(service.metrics().journeys()).isEmpty();
    }

    @Test
    void metricsAreMemoizedWithinTheTtlSoPollingDoesNotRescan() {
        store.runs.add(run("alpha", "a0", OpsRun.State.RUNNING, null,
                NOW.minusSeconds(30), null, OpsRun.Notify.NONE));
        OpsRunQueryService.MetricsSnapshot first = service.metrics();

        // A run appears AFTER the first read; the clock is frozen, so the next
        // call is inside the 15s memo window and must serve the SAME cached
        // snapshot rather than re-scanning the store.
        store.runs.add(run("alpha", "a1", OpsRun.State.RUNNING, null,
                NOW.minusSeconds(20), null, OpsRun.Notify.NONE));

        assertThat(service.metrics())
                .as("same instant is inside the TTL — the cached snapshot is re-served")
                .isSameAs(first);
    }

    @Test
    void metricsRecomputeEachCallWhenTheTtlIsZero() {
        OpsRunQueryService noCache = new OpsRunQueryService(
                store, CLOCK, Duration.ofSeconds(900), Duration.ofSeconds(60), Duration.ZERO);
        store.runs.add(run("alpha", "a0", OpsRun.State.RUNNING, null,
                NOW.minusSeconds(30), null, OpsRun.Notify.NONE));
        assertThat(noCache.metrics().journeys()).hasSize(1);

        store.runs.add(run("beta", "b0", OpsRun.State.RUNNING, null,
                NOW.minusSeconds(20), null, OpsRun.Notify.NONE));
        assertThat(noCache.metrics().journeys())
                .as("TTL=0 disables the memo — the new journey shows immediately")
                .hasSize(2);
    }

    private static OpsRun run(String journeyKey, String runId, OpsRun.State state, String outcome,
                             Instant start, Instant end, OpsRun.Notify notify) {
        return new OpsRun(runId, journeyKey, 1, state, outcome, notify, start, end,
                state == OpsRun.State.RUNNING ? null : "n_done",
                "corr-" + runId, "ntf-" + runId, "rec-" + runId, List.of());
    }
}

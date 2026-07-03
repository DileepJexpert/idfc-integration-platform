package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Server-side filtering + pagination over the run set (D15: the client never
 * fetch-all-and-filters). Sorting is startedAt DESC (newest first) with runId
 * as the deterministic tiebreak, so pages are stable across polls.
 *
 * <p>{@code stuckOnly} uses threshold {@code budget − sweepInterval}: a run
 * surfaces as stuck BEFORE the sweeper converts it to FAILED+notified (D9),
 * giving ops a window on genuinely-live problems, not just corpses.
 */
public class OpsRunQueryService {

    private final OpsRunStore store;
    private final Clock clock;
    private final Duration stuckAfter;
    /** The full liveness budget — a LIVE run will be swept at startedAt + runBudget. */
    private final Duration runBudget;

    public OpsRunQueryService(OpsRunStore store, Clock clock,
                              Duration runBudget, Duration sweepInterval) {
        this.store = store;
        this.clock = clock;
        Duration threshold = runBudget.minus(sweepInterval);
        // A sweep interval >= the budget would make the threshold nonsensical;
        // floor it so stuckOnly still means "approaching the budget".
        this.stuckAfter = threshold.isNegative() || threshold.isZero()
                ? Duration.ofSeconds(1) : threshold;
        this.runBudget = runBudget;
    }

    public record Page(List<OpsRun> items, int page, int size, long totalItems, int totalPages) {
    }

    public record Filter(OpsRun.StatusVocabulary status, String journeyKey,
                         Instant since, Instant until, boolean stuckOnly) {
    }

    public Page list(Filter filter, int page, int size) {
        List<OpsRun> matching = store.scanAll().stream()
                .filter(r -> filter.status() == null || r.status() == filter.status())
                .filter(r -> filter.journeyKey() == null || filter.journeyKey().equals(r.journeyKey()))
                .filter(r -> filter.since() == null
                        || (r.startedAt() != null && !r.startedAt().isBefore(filter.since())))
                .filter(r -> filter.until() == null
                        || (r.startedAt() != null && !r.startedAt().isAfter(filter.until())))
                .filter(r -> !filter.stuckOnly() || isStuck(r))
                .sorted(newestFirst())
                .toList();
        int from = Math.min(page * size, matching.size());
        int to = Math.min(from + size, matching.size());
        int totalPages = matching.isEmpty() ? 0 : (int) Math.ceil(matching.size() / (double) size);
        return new Page(matching.subList(from, to), page, size, matching.size(), totalPages);
    }

    public Optional<OpsRun> detail(String runId) {
        return store.find(runId);
    }

    /**
     * EXACT match across the four id families (no full-text): runId,
     * correlationId, notificationId, sfdcRecordId. A business record the agent
     * re-sent has SEVERAL runs — all are returned, newest first (D12).
     */
    public List<OpsRun> search(String key) {
        Optional<OpsRun> byRunId = store.find(key);
        List<OpsRun> scanned = store.scanAll().stream()
                .filter(r -> key.equals(r.runId()) || key.equals(r.correlationId())
                        || key.equals(r.notificationId()) || key.equals(r.sfdcRecordId()))
                .sorted(newestFirst())
                .toList();
        if (byRunId.isPresent() && scanned.stream().noneMatch(r -> r.runId().equals(key))) {
            // find() fast-path saw a run the scan window missed — include it.
            return java.util.stream.Stream.concat(scanned.stream(), byRunId.stream())
                    .sorted(newestFirst()).toList();
        }
        return scanned;
    }

    /**
     * OPS P2: WHEN the liveness sweeper will act on a live run (startedAt +
     * budget) — the dashboard shows ops when the system will move on its own.
     * Null for terminal runs.
     */
    public Instant sweepDeadline(OpsRun run) {
        if (run.state() != OpsRun.State.RUNNING || run.startedAt() == null) {
            return null;
        }
        return run.startedAt().plus(runBudget);
    }

    public boolean isStuck(OpsRun run) {
        return run.state() == OpsRun.State.RUNNING
                && run.startedAt() != null
                && !run.startedAt().isAfter(clock.instant().minus(stuckAfter));
    }

    public long stuckCount() {
        return store.scanAll().stream().filter(this::isStuck).count();
    }

    private static Comparator<OpsRun> newestFirst() {
        return Comparator.comparing(OpsRun::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OpsRun::runId);
    }
}

package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * Default metrics memo window. {@code /ops/metrics} aggregates the WHOLE run
     * set, so without a memo every operator's 15s poll re-triggers a full store
     * scan — K dashboards ⇒ K scans per window. One poll interval of caching
     * (matching the ops view cadence and the {@code journeys.stuck.count} gauge)
     * makes it ONE scan per window; the snapshot's generatedAt surfaces the
     * ≤15s staleness to the operator.
     */
    private static final Duration DEFAULT_METRICS_TTL = Duration.ofSeconds(15);

    private final OpsRunStore store;
    private final Clock clock;
    private final Duration stuckAfter;
    /** The full liveness budget — a LIVE run will be swept at startedAt + runBudget. */
    private final Duration runBudget;
    /** How long a computed metrics snapshot is served before a re-scan (see {@link #metrics()}). */
    private final Duration metricsTtl;
    /** Last computed snapshot; re-served while inside {@link #metricsTtl}. Volatile: benign recompute race. */
    private volatile MetricsSnapshot cachedMetrics;

    /** Production ctor — memoizes {@code /ops/metrics} for one poll window (15s). */
    public OpsRunQueryService(OpsRunStore store, Clock clock,
                              Duration runBudget, Duration sweepInterval) {
        this(store, clock, runBudget, sweepInterval, DEFAULT_METRICS_TTL);
    }

    /**
     * TTL-explicit ctor: {@code metricsTtl} bounds how stale a served metrics
     * snapshot may be. {@link Duration#ZERO} disables the memo (every call
     * re-scans) — used by tests that assert freshness.
     */
    public OpsRunQueryService(OpsRunStore store, Clock clock, Duration runBudget,
                              Duration sweepInterval, Duration metricsTtl) {
        this.store = store;
        this.clock = clock;
        Duration threshold = runBudget.minus(sweepInterval);
        // A sweep interval >= the budget would make the threshold nonsensical;
        // floor it so stuckOnly still means "approaching the budget".
        this.stuckAfter = threshold.isNegative() || threshold.isZero()
                ? Duration.ofSeconds(1) : threshold;
        this.runBudget = runBudget;
        this.metricsTtl = metricsTtl == null ? Duration.ZERO : metricsTtl;
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

    /** Per-journey aggregate at a point in time (Temporal-style "Workflows" metrics). */
    public record JourneyMetrics(
            String journeyKey, long total, long running,
            long completedApproved, long completedDeclined, long failed, long stuck,
            long startedLast24h, Long p50Millis, Long p95Millis) {
    }

    public record MetricsSnapshot(Instant generatedAt, List<JourneyMetrics> journeys) {
    }

    /**
     * Aggregate the visible run set into per-journey metrics — counts by the C.4
     * status vocabulary, the stuck count, a 24h throughput proxy, and end-to-end
     * duration p50/p95 over runs that have ended. Same scanAll() source as the
     * runs list; ids and numbers only, so it stays inside the no-payload contract.
     */
    public MetricsSnapshot metrics() {
        Instant now = clock.instant();
        MetricsSnapshot cached = cachedMetrics;
        // Serve the memo for one poll window so concurrent dashboards don't each
        // trigger a full store scan (the stuck gauge caches its scan the same way).
        if (cached != null
                && Duration.between(cached.generatedAt(), now).compareTo(metricsTtl) < 0) {
            return cached;
        }
        MetricsSnapshot fresh = computeMetrics(now);
        cachedMetrics = fresh;
        return fresh;
    }

    private MetricsSnapshot computeMetrics(Instant now) {
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Map<String, List<OpsRun>> byJourney = new HashMap<>();
        for (OpsRun r : store.scanAll()) {
            byJourney.computeIfAbsent(r.journeyKey() == null ? "(unknown)" : r.journeyKey(),
                    k -> new ArrayList<>()).add(r);
        }
        List<JourneyMetrics> journeys = new ArrayList<>();
        for (Map.Entry<String, List<OpsRun>> e : byJourney.entrySet()) {
            long running = 0, approved = 0, declined = 0, failed = 0, stuck = 0, last24h = 0;
            List<Long> durations = new ArrayList<>();
            for (OpsRun r : e.getValue()) {
                switch (r.status()) {
                    case RUNNING -> running++;
                    case COMPLETED_APPROVED -> approved++;
                    case COMPLETED_DECLINED -> declined++;
                    case FAILED_SFDC_NOTIFIED, FAILED_NOTIFY_PENDING -> failed++;
                }
                if (isStuck(r)) {
                    stuck++;
                }
                if (r.startedAt() != null && !r.startedAt().isBefore(dayAgo)) {
                    last24h++;
                }
                if (r.startedAt() != null && r.endedAt() != null) {
                    long ms = Duration.between(r.startedAt(), r.endedAt()).toMillis();
                    if (ms >= 0) {
                        durations.add(ms);
                    }
                }
            }
            durations.sort(null);
            journeys.add(new JourneyMetrics(e.getKey(), e.getValue().size(), running,
                    approved, declined, failed, stuck, last24h,
                    percentile(durations, 50), percentile(durations, 95)));
        }
        journeys.sort(Comparator.comparingLong(JourneyMetrics::total).reversed()
                .thenComparing(JourneyMetrics::journeyKey));
        return new MetricsSnapshot(now, journeys);
    }

    /** Nearest-rank percentile over an ascending list; null when empty. */
    private static Long percentile(List<Long> ascending, int p) {
        if (ascending.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * ascending.size());
        int idx = Math.min(ascending.size() - 1, Math.max(0, rank - 1));
        return ascending.get(idx);
    }

    private static Comparator<OpsRun> newestFirst() {
        return Comparator.comparing(OpsRun::startedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(OpsRun::runId);
    }
}

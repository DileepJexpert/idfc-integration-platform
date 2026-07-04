package com.idfcfirstbank.integration.platform.opsquery.web;

import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunQueryService;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsTransition;

import java.time.Instant;
import java.util.List;

/**
 * ALLOW-LIST wire shapes (B.3): every field is an id, a timestamp, an enum name
 * or a bounded list of the same — there is structurally no field a payload
 * could ride in, and entities are never serialized directly. The no-payload
 * reflection test locks this for every future edit.
 */
public final class OpsDtos {

    private OpsDtos() {
    }

    public record RunSummaryDto(
            String runId,
            String journeyKey,
            int journeyVersion,
            String status,           // C.4 vocabulary, computed server-side
            Instant startedAt,
            Instant endedAt,
            String correlationId,
            String notificationId,
            String sfdcRecordId,
            boolean stuck,
            /** OPS P2: when the sweeper will act on a LIVE run; null once terminal. */
            Instant sweepDeadline) {

        static RunSummaryDto of(OpsRun r, boolean stuck, Instant sweepDeadline) {
            return new RunSummaryDto(r.runId(), r.journeyKey(), r.journeyVersion(),
                    r.status().name(), r.startedAt(), r.endedAt(),
                    r.correlationId(), r.notificationId(), r.sfdcRecordId(), stuck, sweepDeadline);
        }
    }

    /**
     * OPS P2: per-node execution stats — dispatch attempts (T2 retry ladder)
     * and, for terminally-failed nodes, the failure-class ENUM NAME
     * (TRANSIENT / PERMANENT / AMBIGUOUS / BREAKER_OPEN). Enum names only:
     * a free-text reason is the classic PII smuggling route and cannot exist
     * here (the no-payload proof walks this record too).
     */
    public record NodeStatDto(String nodeId, int attempts, String failureClass) {
    }

    public record TransitionDto(int seq, String nodeId, String status, Instant at, boolean late) {

        static TransitionDto of(OpsTransition t) {
            return new TransitionDto(t.seq(), t.nodeId(), t.status(), t.at(), t.late());
        }
    }

    public record RunDetailDto(
            String runId,
            String journeyKey,
            int journeyVersion,
            String status,
            String sfdcNotified,      // NONE / PENDING / SENT
            Instant startedAt,
            Instant endedAt,
            String terminalNodeId,
            String terminalOutcome,
            String correlationId,
            String notificationId,
            String sfdcRecordId,
            List<TransitionDto> transitions,
            String dlqTopicRef,       // pointer only; DLQ CONTENT is Brod's (masked) job
            boolean stuck,
            /** OPS P2: when the sweeper will act on a LIVE run; null once terminal. */
            Instant sweepDeadline,
            /** OPS P2: per-node attempts + failure classes (ids and enum names only). */
            List<NodeStatDto> nodeStats,
            /** OPS P2: the node whose failure started the compensation saga. */
            String compensationOf,
            /** OPS P2: compensation node ids still to be undone (head = in flight). */
            List<String> compensationPending) {

        static RunDetailDto of(OpsRun r, boolean stuck, String dlqTopicRef, Instant sweepDeadline) {
            return new RunDetailDto(r.runId(), r.journeyKey(), r.journeyVersion(),
                    r.status().name(), r.sfdcNotified().name(), r.startedAt(), r.endedAt(),
                    r.terminalNodeId(), r.outcome(), r.correlationId(), r.notificationId(),
                    r.sfdcRecordId(),
                    r.transitions().stream().map(TransitionDto::of).toList(),
                    dlqTopicRef, stuck, sweepDeadline, nodeStatsOf(r),
                    r.compensationOf(), r.compensationPending());
        }

        /** Union of the attempts and failure-class maps, flattened id-shaped, by nodeId. */
        private static List<NodeStatDto> nodeStatsOf(OpsRun r) {
            java.util.TreeSet<String> nodeIds = new java.util.TreeSet<>(r.dispatchAttempts().keySet());
            nodeIds.addAll(r.nodeFailureClasses().keySet());
            return nodeIds.stream()
                    .map(id -> new NodeStatDto(id,
                            r.dispatchAttempts().getOrDefault(id, 0),
                            r.nodeFailureClasses().get(id)))
                    .toList();
        }
    }

    public record PageDto(List<RunSummaryDto> items, int page, int size,
                          long totalItems, int totalPages) {

        static PageDto of(OpsRunQueryService.Page page, java.util.function.Predicate<OpsRun> stuck,
                          java.util.function.Function<OpsRun, Instant> sweepDeadline) {
            return new PageDto(
                    page.items().stream()
                            .map(r -> RunSummaryDto.of(r, stuck.test(r), sweepDeadline.apply(r)))
                            .toList(),
                    page.page(), page.size(), page.totalItems(), page.totalPages());
        }
    }

    /**
     * Per-journey aggregate (Temporal-style "Workflows" metrics). RAW counts +
     * duration percentiles only — rates (approval/failure %) are derived
     * client-side, so no {@code double} field crosses the allow-listed wire.
     */
    public record JourneyMetricDto(
            String journeyKey, long total, long running,
            long completedApproved, long completedDeclined, long failed, long stuck,
            long startedLast24h, Long p50Millis, Long p95Millis) {

        static JourneyMetricDto of(OpsRunQueryService.JourneyMetrics m) {
            return new JourneyMetricDto(m.journeyKey(), m.total(), m.running(),
                    m.completedApproved(), m.completedDeclined(), m.failed(), m.stuck(),
                    m.startedLast24h(), m.p50Millis(), m.p95Millis());
        }
    }

    public record MetricsDto(Instant generatedAt, List<JourneyMetricDto> journeys) {

        static MetricsDto of(OpsRunQueryService.MetricsSnapshot snapshot) {
            return new MetricsDto(snapshot.generatedAt(),
                    snapshot.journeys().stream().map(JourneyMetricDto::of).toList());
        }
    }

    public record ErrorDto(String error, String message) {
    }
}

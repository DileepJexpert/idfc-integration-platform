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
            boolean stuck) {

        static RunSummaryDto of(OpsRun r, boolean stuck) {
            return new RunSummaryDto(r.runId(), r.journeyKey(), r.journeyVersion(),
                    r.status().name(), r.startedAt(), r.endedAt(),
                    r.correlationId(), r.notificationId(), r.sfdcRecordId(), stuck);
        }
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
            boolean stuck) {

        static RunDetailDto of(OpsRun r, boolean stuck, String dlqTopicRef) {
            return new RunDetailDto(r.runId(), r.journeyKey(), r.journeyVersion(),
                    r.status().name(), r.sfdcNotified().name(), r.startedAt(), r.endedAt(),
                    r.terminalNodeId(), r.outcome(), r.correlationId(), r.notificationId(),
                    r.sfdcRecordId(),
                    r.transitions().stream().map(TransitionDto::of).toList(),
                    dlqTopicRef, stuck);
        }
    }

    public record PageDto(List<RunSummaryDto> items, int page, int size,
                          long totalItems, int totalPages) {

        static PageDto of(OpsRunQueryService.Page page, java.util.function.Predicate<OpsRun> stuck) {
            return new PageDto(
                    page.items().stream().map(r -> RunSummaryDto.of(r, stuck.test(r))).toList(),
                    page.page(), page.size(), page.totalItems(), page.totalPages());
        }
    }

    public record ErrorDto(String error, String message) {
    }
}

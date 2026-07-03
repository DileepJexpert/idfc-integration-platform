package com.idfcfirstbank.integration.platform.opsquery.web;

import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRunQueryService;
import com.idfcfirstbank.integration.platform.opsquery.web.OpsDtos.ErrorDto;
import com.idfcfirstbank.integration.platform.opsquery.web.OpsDtos.PageDto;
import com.idfcfirstbank.integration.platform.opsquery.web.OpsDtos.RunDetailDto;
import com.idfcfirstbank.integration.platform.opsquery.web.OpsDtos.RunSummaryDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * The audited ops read window (B.3) — GET endpoints ONLY, by construction (the
 * mutation-free proof test locks this). Auth + audit happen in the ops filter;
 * this layer is parameter parsing and DTO mapping.
 */
@RestController
@RequestMapping("/ops")
public class OpsRunController {

    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 50;

    private final OpsRunQueryService service;

    public OpsRunController(OpsRunQueryService service) {
        this.service = service;
    }

    @GetMapping("/runs")
    public PageDto runs(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String journeyKey,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "false") boolean stuckOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        OpsRunQueryService.Filter filter = new OpsRunQueryService.Filter(
                parseStatus(status), blankToNull(journeyKey),
                parseInstant("since", since), parseInstant("until", until), stuckOnly);
        OpsRunQueryService.Page result = service.list(
                filter, Math.max(0, page), clamp(size));
        return PageDto.of(result, service::isStuck, service::sweepDeadline);
    }

    @GetMapping("/runs/search")
    public List<RunSummaryDto> search(@RequestParam String key) {
        if (key.isBlank()) {
            throw new BadRequest("query parameter 'key' must be a non-blank exact id"
                    + " (runId | correlationId | notificationId | sfdcRecordId)");
        }
        return service.search(key.trim()).stream()
                .map(r -> RunSummaryDto.of(r, service.isStuck(r), service.sweepDeadline(r)))
                .toList();
    }

    @GetMapping("/runs/{runId}")
    public ResponseEntity<RunDetailDto> detail(@PathVariable String runId) {
        return service.detail(runId)
                .map(r -> ResponseEntity.ok(RunDetailDto.of(
                        r, service.isStuck(r), dlqRef(r), service.sweepDeadline(r))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Pointer ONLY (D13): failed runs surface the ops-events DLQ topic name as a
     * starting point for Brod; offsets are not tracked in v1 (documented gap).
     */
    private static String dlqRef(OpsRun run) {
        return switch (run.status()) {
            case FAILED_SFDC_NOTIFIED, FAILED_NOTIFY_PENDING -> "orig.sfdc.dlq.v1";
            default -> null;
        };
    }

    private static OpsRun.StatusVocabulary parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OpsRun.StatusVocabulary.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequest("unknown status '" + raw + "' (allowed: "
                    + List.of(OpsRun.StatusVocabulary.values()) + ")");
        }
    }

    private static Instant parseInstant(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequest("'" + name + "' must be an ISO-8601 instant, e.g. 2026-07-02T10:00:00Z");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int clamp(int size) {
        return Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ErrorDto> badRequest(BadRequest e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorDto("BAD_REQUEST", e.getMessage()));
    }
}

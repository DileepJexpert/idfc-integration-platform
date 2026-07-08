package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
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
 * The audited sync-lane invocation read window — GET endpoints ONLY (no mutation:
 * this is an audit view). Auth + actor are enforced by {@link OpsAuditAuthFilter} on
 * {@code /ops/*}; this layer is parameter parsing + PII-safe DTO mapping. These are
 * SYNC in-thread calls, not journey runs — a separate list, never faked as runs.
 */
@RestController
@RequestMapping("/ops/sync-invocations")
public class SyncInvocationController {

    private final SyncInvocationQueryService service;

    public SyncInvocationController(SyncInvocationQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageDto list(
            @RequestParam(required = false) String capability,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + SyncInvocationQueryService.DEFAULT_PAGE_SIZE) int size) {

        SyncInvocationQueryService.Filter filter = new SyncInvocationQueryService.Filter(
                blankToNull(capability), blankToNull(source), parseOutcome(outcome),
                parseInstant("since", since), parseInstant("until", until));
        SyncInvocationQueryService.Page result = service.list(filter, page, size);
        return PageDto.of(result);
    }

    @GetMapping("/{invocationId}")
    public ResponseEntity<InvocationDto> detail(@PathVariable String invocationId) {
        return service.detail(invocationId)
                .map(InvocationDto::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-key/{idempotencyKey}")
    public List<InvocationDto> byKey(@PathVariable String idempotencyKey) {
        return service.byIdempotencyKey(idempotencyKey).stream().map(InvocationDto::of).toList();
    }

    // --- parsing helpers -------------------------------------------------------------

    private static SyncOutcome parseOutcome(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SyncOutcome.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequest("unknown outcome '" + raw + "' (allowed: "
                    + List.of(SyncOutcome.values()) + ")");
        }
    }

    private static Instant parseInstant(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new BadRequest("'" + name + "' must be an ISO-8601 instant, e.g. 2026-07-07T10:00:00Z");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // --- DTOs (ids only — no PII) ----------------------------------------------------

    public record InvocationDto(
            String invocationId, String capability, String operation, String source,
            String idempotencyKey, String correlationId, String transactionId,
            String outcome, String errorClass, String errorCode,
            String startedAt, long durationMs, boolean deduped) {

        static InvocationDto of(SyncInvocation i) {
            return new InvocationDto(
                    i.invocationId(), i.capabilityKey(), i.operation(), i.source(),
                    i.idempotencyKey(), i.correlationId(), i.transactionId(),
                    i.outcome() == null ? null : i.outcome().name(), i.errorClass(), i.errorCode(),
                    i.startedAt() == null ? null : i.startedAt().toString(), i.durationMs(), i.deduped());
        }
    }

    public record PageDto(List<InvocationDto> items, int page, int size, int totalItems, int totalPages) {
        static PageDto of(SyncInvocationQueryService.Page p) {
            return new PageDto(p.items().stream().map(InvocationDto::of).toList(),
                    p.page(), p.size(), p.totalItems(), p.totalPages());
        }
    }

    static class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    @ExceptionHandler(BadRequest.class)
    ResponseEntity<ErrorDto> badRequest(BadRequest e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorDto("BAD_REQUEST", e.getMessage()));
    }

    public record ErrorDto(String error, String message) {
    }
}

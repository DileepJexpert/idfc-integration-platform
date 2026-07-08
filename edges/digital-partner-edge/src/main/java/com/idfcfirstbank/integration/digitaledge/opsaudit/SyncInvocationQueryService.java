package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Read-only query surface over the sync-audit store: server-side filter + newest-first
 * pagination, single-record read, and business-dedup-id lookup. Mirrors the journey
 * ops query service — the UI never queries a store directly.
 */
@Service
public class SyncInvocationQueryService {

    static final int MAX_PAGE_SIZE = 200;
    static final int DEFAULT_PAGE_SIZE = 50;

    private final SyncInvocationStore store;

    public SyncInvocationQueryService(SyncInvocationStore store) {
        this.store = store;
    }

    public Page list(Filter filter, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<SyncInvocation> matched = store.scanAll().stream()
                .filter(filter::matches)
                .sorted(Comparator.comparing(SyncInvocation::startedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        int total = matched.size();
        int from = Math.min(p * s, total);
        int to = Math.min(from + s, total);
        return new Page(matched.subList(from, to), p, s, total, (int) Math.ceil((double) total / s));
    }

    public Optional<SyncInvocation> detail(String invocationId) {
        return store.find(invocationId);
    }

    public List<SyncInvocation> byIdempotencyKey(String idempotencyKey) {
        return store.findByIdempotencyKey(idempotencyKey).stream()
                .sorted(Comparator.comparing(SyncInvocation::startedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
    }

    /** Server-side filter — all criteria optional, all AND-ed. */
    public record Filter(String capabilityKey, String source, SyncOutcome outcome, Instant since, Instant until) {

        boolean matches(SyncInvocation i) {
            if (capabilityKey != null && !capabilityKey.equals(i.capabilityKey())) {
                return false;
            }
            if (source != null && !source.equalsIgnoreCase(i.source())) {
                return false;
            }
            if (outcome != null && outcome != i.outcome()) {
                return false;
            }
            if (since != null && (i.startedAt() == null || i.startedAt().isBefore(since))) {
                return false;
            }
            if (until != null && (i.startedAt() == null || i.startedAt().isAfter(until))) {
                return false;
            }
            return true;
        }
    }

    public record Page(List<SyncInvocation> items, int page, int size, int totalItems, int totalPages) {
    }
}

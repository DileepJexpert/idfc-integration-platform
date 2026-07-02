package com.idfcfirstbank.integration.platform.opsquery.domain;

import com.idfcfirstbank.integration.platform.opsquery.FixtureRuns;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp.SeedableOpsRunStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D15 at Diwali scale (10k synthetic runs): stable newest-first pages, correct
 * page math at the tail, filters composed with pagination — all SERVER-side.
 */
class PaginationAt10kTest {

    private static final Instant NOW = Instant.parse("2026-07-02T12:00:00Z");
    private static final int TOTAL = 10_000;

    private final SeedableOpsRunStore store = new SeedableOpsRunStore();
    private final OpsRunQueryService service = new OpsRunQueryService(
            store, Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofSeconds(900), Duration.ofSeconds(60));

    @BeforeEach
    void seedTenThousand() {
        for (int n = 0; n < TOTAL; n++) {
            // one run per second going back — ids zero-padded so ordering is checkable
            store.runs.add(FixtureRuns.completed(
                    "ji-%05d".formatted(n), NOW.minusSeconds(n + 1L),
                    n % 2 == 0 ? "APPROVED" : "REJECTED"));
        }
    }

    private static final OpsRunQueryService.Filter ALL =
            new OpsRunQueryService.Filter(null, null, null, null, false);

    @Test
    void pagesAreStableNewestFirstWithCorrectMath() {
        OpsRunQueryService.Page first = service.list(ALL, 0, 50);
        assertThat(first.totalItems()).isEqualTo(TOTAL);
        assertThat(first.totalPages()).isEqualTo(200);
        assertThat(first.items()).hasSize(50);
        assertThat(first.items().get(0).runId()).isEqualTo("ji-00000"); // newest
        assertThat(first.items().get(49).runId()).isEqualTo("ji-00049");

        OpsRunQueryService.Page second = service.list(ALL, 1, 50);
        assertThat(second.items().get(0).runId()).isEqualTo("ji-00050");

        // The tail page is partial, never an index error.
        OpsRunQueryService.Page last = service.list(ALL, 199, 50);
        assertThat(last.items()).hasSize(50);
        OpsRunQueryService.Page past = service.list(ALL, 500, 50);
        assertThat(past.items()).isEmpty();
        assertThat(past.totalItems()).isEqualTo(TOTAL);
    }

    @Test
    void filtersComposeWithPaginationServerSide() {
        OpsRunQueryService.Page declined = service.list(new OpsRunQueryService.Filter(
                OpsRun.StatusVocabulary.COMPLETED_DECLINED, null, null, null, false), 0, 100);
        assertThat(declined.totalItems()).isEqualTo(TOTAL / 2);
        assertThat(declined.items()).hasSize(100)
                .allMatch(r -> r.status() == OpsRun.StatusVocabulary.COMPLETED_DECLINED);

        OpsRunQueryService.Page window = service.list(new OpsRunQueryService.Filter(
                null, null, NOW.minusSeconds(100), NOW, false), 0, 200);
        assertThat(window.totalItems()).isEqualTo(100);
    }
}

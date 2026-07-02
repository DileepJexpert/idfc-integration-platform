package com.idfcfirstbank.integration.platform.opsquery.web;

import com.idfcfirstbank.integration.platform.opsquery.FixtureRuns;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp.SeedableOpsRunStore;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsRun;
import com.idfcfirstbank.integration.platform.opsquery.domain.OpsTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three ops endpoints over MockMvc: server-side filters (status vocabulary,
 * journeyKey, time window, stuckOnly), exact-key search across id families, the
 * detail timeline (sequence-ordered, late-flagged), and 400s on bad params.
 */
@SpringBootTest(classes = OpsQueryTestApp.class, properties = {
        "idfc.ops.auth-token=test-ops-token",
        "idfc.engine.liveness.run-budget-seconds=900",
        "idfc.engine.liveness.sweep-interval-ms=60000",
})
@AutoConfigureMockMvc
class OpsApiQueryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SeedableOpsRunStore store;

    private final Instant now = Instant.now();

    private MockHttpServletRequestBuilder authed(String path) {
        return get(path).header("X-Ops-Token", "test-ops-token").header("X-User-Id", "ops-meera");
    }

    @BeforeEach
    void seed() {
        store.runs.clear();
        store.runs.add(FixtureRuns.running("ji-run", now.minusSeconds(30)));
        store.runs.add(FixtureRuns.running("ji-stuck", now.minusSeconds(2_000)));
        store.runs.add(FixtureRuns.completed("ji-approved", now.minusSeconds(600), "APPROVED"));
        store.runs.add(FixtureRuns.completed("ji-declined", now.minusSeconds(700), "REJECTED"));
        store.runs.add(FixtureRuns.failed("ji-notified", now.minusSeconds(800), OpsRun.Notify.SENT));
        store.runs.add(FixtureRuns.failed("ji-unnotified", now.minusSeconds(900), OpsRun.Notify.PENDING));
    }

    @Test
    void listsNewestFirstWithTheComputedVocabulary() throws Exception {
        mockMvc.perform(authed("/ops/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(6)))
                .andExpect(jsonPath("$.items[0].runId", is("ji-run")))
                .andExpect(jsonPath("$.items[0].status", is("RUNNING")))
                .andExpect(jsonPath("$.items[1].runId", is("ji-approved")))
                .andExpect(jsonPath("$.items[2].status", is("COMPLETED_DECLINED")))
                .andExpect(jsonPath("$.items[3].status", is("FAILED_SFDC_NOTIFIED")))
                .andExpect(jsonPath("$.items[4].status", is("FAILED_NOTIFY_PENDING")));
    }

    @Test
    void filtersByStatusVocabularyAndStuckOnlyAndWindow() throws Exception {
        mockMvc.perform(authed("/ops/runs").param("status", "COMPLETED_DECLINED"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].runId", is("ji-declined")));

        mockMvc.perform(authed("/ops/runs").param("stuckOnly", "true"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].runId", is("ji-stuck")))
                .andExpect(jsonPath("$.items[0].stuck", is(true)));

        mockMvc.perform(authed("/ops/runs")
                        .param("since", now.minusSeconds(750).toString())
                        .param("until", now.minusSeconds(500).toString()))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].runId", is("ji-approved")))
                .andExpect(jsonPath("$.items[1].runId", is("ji-declined")));

        mockMvc.perform(authed("/ops/runs").param("journeyKey", "no-such-journey"))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void searchReturnsEveryRunOfAReSentBusinessRecord() throws Exception {
        store.runs.clear();
        store.runs.add(FixtureRuns.withSfdcRecord(
                FixtureRuns.failed("ji-first", now.minusSeconds(900), OpsRun.Notify.SENT), "REC-1"));
        store.runs.add(FixtureRuns.withSfdcRecord(
                FixtureRuns.completed("ji-resent", now.minusSeconds(100), "APPROVED"), "REC-1"));

        mockMvc.perform(authed("/ops/runs/search").param("key", "REC-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].runId", is("ji-resent")))
                .andExpect(jsonPath("$[1].runId", is("ji-first")));

        mockMvc.perform(authed("/ops/runs/search").param("key", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailCarriesTheSequenceOrderedTimelineAndNotifyState() throws Exception {
        store.runs.add(new OpsRun("ji-detail", "loan-origination", 2, OpsRun.State.COMPLETED,
                "APPROVED", OpsRun.Notify.SENT, now.minusSeconds(500), now.minusSeconds(400),
                "n_done", "corr-d", "ntf-d", "rec-d",
                List.of(new OpsTransition(0, "n_verify", "DISPATCHED", now.minusSeconds(500), false),
                        new OpsTransition(1, "n_verify", "COMPLETED", now.minusSeconds(450), false),
                        new OpsTransition(2, "n_late", "COMPLETED", now.minusSeconds(100), true))));

        mockMvc.perform(authed("/ops/runs/ji-detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journeyVersion", is(2)))
                .andExpect(jsonPath("$.status", is("COMPLETED_APPROVED")))
                .andExpect(jsonPath("$.sfdcNotified", is("SENT")))
                .andExpect(jsonPath("$.terminalNodeId", is("n_done")))
                .andExpect(jsonPath("$.transitions", hasSize(3)))
                .andExpect(jsonPath("$.transitions[0].seq", is(0)))
                .andExpect(jsonPath("$.transitions[2].late", is(true)))
                .andExpect(jsonPath("$.dlqTopicRef").doesNotExist());

        mockMvc.perform(authed("/ops/runs/ji-unnotified"))
                .andExpect(jsonPath("$.status", is("FAILED_NOTIFY_PENDING")))
                .andExpect(jsonPath("$.dlqTopicRef", is("orig.sfdc.dlq.v1")));

        mockMvc.perform(authed("/ops/runs/no-such-run")).andExpect(status().isNotFound());
    }

    @Test
    void badParametersAre400sWithAMessage() throws Exception {
        mockMvc.perform(authed("/ops/runs").param("status", "GREENISH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("BAD_REQUEST")));
        mockMvc.perform(authed("/ops/runs").param("since", "yesterday-ish"))
                .andExpect(status().isBadRequest());
    }
}

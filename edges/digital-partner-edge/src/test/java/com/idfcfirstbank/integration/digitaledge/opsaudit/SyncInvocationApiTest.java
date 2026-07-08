package com.idfcfirstbank.integration.digitaledge.opsaudit;

import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audited {@code /ops/sync-invocations} surface via MockMvc: the read endpoints
 * return the persisted records, and the fail-closed {@link OpsAuditAuthFilter} rejects
 * anything without a valid {@code X-Ops-Token} + {@code X-User-Id}.
 */
class SyncInvocationApiTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        InMemorySyncInvocationStore store = new InMemorySyncInvocationStore();
        store.record(new SyncInvocation("sync-a", "imps-disbursal", "transfer", "INDMONEY",
                "idem-1", "corr-1", "TXN-1", SyncOutcome.SUCCESS, null, null,
                Instant.parse("2026-07-07T10:00:00Z"), 42, false));

        SyncInvocationController controller =
                new SyncInvocationController(new SyncInvocationQueryService(store));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new OpsAuditAuthFilter("dev-ops-token"))
                .build();
    }

    @Test
    void fails_closed_without_token_actor_or_with_wrong_token() throws Exception {
        mvc.perform(get("/ops/sync-invocations"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/sync-invocations").header("X-Ops-Token", "dev-ops-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/sync-invocations")
                        .header("X-Ops-Token", "wrong").header("X-User-Id", "you@bank"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_returns_records_when_authed() throws Exception {
        mvc.perform(get("/ops/sync-invocations")
                        .header("X-Ops-Token", "dev-ops-token").header("X-User-Id", "you@bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].capability").value("imps-disbursal"))
                .andExpect(jsonPath("$.items[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.items[0].transactionId").value("TXN-1"));
    }

    @Test
    void by_key_lookup_returns_the_record() throws Exception {
        mvc.perform(get("/ops/sync-invocations/by-key/idem-1")
                        .header("X-Ops-Token", "dev-ops-token").header("X-User-Id", "you@bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].idempotencyKey").value("idem-1"))
                .andExpect(jsonPath("$[0].transactionId").value("TXN-1"));
    }
}

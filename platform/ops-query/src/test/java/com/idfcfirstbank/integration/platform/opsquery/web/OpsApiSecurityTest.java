package com.idfcfirstbank.integration.platform.opsquery.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.idfcfirstbank.integration.platform.opsquery.FixtureRuns;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp;
import com.idfcfirstbank.integration.platform.opsquery.OpsQueryTestApp.SeedableOpsRunStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B.3 transport rules: the ops window opens ONLY to the ops token + an actor
 * identity, EVERY attempt (allowed or refused) is audit-logged with the actor,
 * and the module is read-only BY CONSTRUCTION — the mapping proof fails compile
 * -time-adjacent (at test time) if anyone ever adds a mutation endpoint.
 */
@SpringBootTest(classes = OpsQueryTestApp.class, properties = {
        "idfc.ops.auth-token=test-ops-token",
})
@AutoConfigureMockMvc
class OpsApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SeedableOpsRunStore store;
    @Autowired
    private RequestMappingHandlerMapping mappings;

    private ListAppender<ILoggingEvent> auditLines;

    @BeforeEach
    void seed() {
        store.runs.clear();
        store.runs.add(FixtureRuns.completed("ji-sec-1", Instant.now().minusSeconds(120), "APPROVED"));
        auditLines = new ListAppender<>();
        auditLines.start();
        ((Logger) LoggerFactory.getLogger("ops.audit")).addAppender(auditLines);
    }

    @AfterEach
    void detach() {
        ((Logger) LoggerFactory.getLogger("ops.audit")).detachAppender(auditLines);
    }

    @Test
    void missingOrWrongTokenIs401AndAudited() throws Exception {
        mockMvc.perform(get("/ops/runs").header("X-User-Id", "ops-meera"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/ops/runs")
                        .header("X-Ops-Token", "wrong").header("X-User-Id", "ops-meera"))
                .andExpect(status().isUnauthorized());

        assertThat(auditLines.list)
                .as("refused reads are audit-logged too")
                .hasSize(2)
                .allSatisfy(line -> assertThat(line.getFormattedMessage())
                        .contains("actor=ops-meera").contains("allowed=false"));
    }

    @Test
    void theRegistryTokenDoesNotOpenTheOpsWindow() throws Exception {
        // D14: the two secrets are different values in different headers.
        mockMvc.perform(get("/ops/runs")
                        .header("X-Registry-Token", "test-ops-token")
                        .header("X-User-Id", "ops-meera"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingActorIs401EvenWithAValidToken() throws Exception {
        mockMvc.perform(get("/ops/runs").header("X-Ops-Token", "test-ops-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "X-User-Id header is required for the ops API"));
        assertThat(auditLines.list.get(0).getFormattedMessage()).contains("actor=-");
    }

    @Test
    void anAllowedReadIsAuditedWithTheActor() throws Exception {
        mockMvc.perform(get("/ops/runs/ji-sec-1")
                        .header("X-Ops-Token", "test-ops-token").header("X-User-Id", "ops-meera"))
                .andExpect(status().isOk());

        assertThat(auditLines.list).hasSize(1);
        assertThat(auditLines.list.get(0).getFormattedMessage())
                .contains("actor=ops-meera").contains("allowed=true").contains("/ops/runs/ji-sec-1");
    }

    @Test
    void theModuleIsReadOnlyByConstruction() {
        Map<RequestMappingInfo, HandlerMethod> handlers = mappings.getHandlerMethods();
        handlers.forEach((info, method) -> {
            boolean opsMapping = info.getPathPatternsCondition() != null
                    && info.getPathPatternsCondition().getPatterns().stream()
                    .anyMatch(p -> p.getPatternString().startsWith("/ops"));
            if (opsMapping) {
                Set<org.springframework.web.bind.annotation.RequestMethod> methods =
                        info.getMethodsCondition().getMethods();
                assertThat(methods)
                        .as("ZERO mutation endpoints may exist in the ops module (%s)", info)
                        .isNotEmpty()
                        .allMatch(m -> m.asHttpMethod().equals(HttpMethod.GET));
            }
        });
    }
}

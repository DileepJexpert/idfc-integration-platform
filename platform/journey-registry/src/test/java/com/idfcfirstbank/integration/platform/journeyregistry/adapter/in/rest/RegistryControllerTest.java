package com.idfcfirstbank.integration.platform.journeyregistry.adapter.in.rest;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The registry over REAL HTTP semantics (MockMvc through the full filter +
 * MVC chain): service-token 401s, actor-identity 401s, maker-checker 403s,
 * lifecycle 409s, validation 422s — and the designer-facing JSON vocabulary
 * (camelCase statuses, ValidationIssue shape, §7 config as real JSON).
 *
 * <p>Ordered as ONE maker-checker story so the HTTP layer is exercised the way
 * the designer will drive it.
 */
@SpringBootTest(properties = {
        "idfc.registry.auth-token=test-registry-token",
        "idfc.registry.store=in-memory"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistryControllerTest {

    private static final String TOKEN = "test-registry-token";
    private static final String MAKER = "maker-asha";
    private static final String CHECKER = "checker-vikram";

    private static final String VALID_CONFIG_BODY = """
            {
              "config": {
                "journeyKey": "spoofed-key",
                "version": 999,
                "startNodeId": "n_start",
                "nodes": [
                  {"id": "n_start", "type": "task", "capability": "kyc", "operation": "verify",
                   "next": ["n_done"]},
                  {"id": "n_done", "type": "terminal", "status": "completed"}
                ]
              },
              "note": "first cut"
            }""";

    private static final String BROKEN_CONFIG_BODY = """
            {"config": {"startNodeId": "a", "nodes": [{"id": "a", "type": "task", "next": ["ghost"]}]}}""";

    @Autowired
    private MockMvc mockMvc;

    // ---- transport guards -----------------------------------------------------

    @Test
    @Order(1)
    void withoutServiceTokenEveryApiCallIs401() throws Exception {
        mockMvc.perform(get("/api/v1/journeys"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("UNAUTHENTICATED")));
        mockMvc.perform(get("/api/v1/journeys").header("X-Registry-Token", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(2)
    void corsPreflightPassesWithoutToken() throws Exception {
        mockMvc.perform(options("/api/v1/journeys")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Registry-Token,X-User-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
    }

    @Test
    @Order(3)
    void mutationsWithoutActorIdentityAre401() throws Exception {
        mockMvc.perform(post("/api/v1/journeys")
                        .header("X-Registry-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"pl-express\", \"name\": \"PL Express\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("X-User-Id header is required for this operation")));
    }

    // ---- the maker-checker story over HTTP ---------------------------------------

    @Test
    @Order(10)
    void makerCreatesJourneyAndDraft() throws Exception {
        mockMvc.perform(post("/api/v1/journeys")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\": \"pl-express\", \"name\": \"PL Express\", \"businessLine\": \"PL\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is("pl-express")))
                .andExpect(jsonPath("$.activeVersion", nullValue()));

        mockMvc.perform(post("/api/v1/journeys/pl-express/versions")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.status", is("draft")))
                .andExpect(jsonPath("$.authorId", is(MAKER)))
                // Server-owned identity: the client's spoofed journeyKey/version lost.
                .andExpect(jsonPath("$.config.journeyKey", is("pl-express")))
                .andExpect(jsonPath("$.config.version", is(1)));
    }

    @Test
    @Order(11)
    void secondDraftIs409() throws Exception {
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("CONFLICT")));
    }

    @Test
    @Order(12)
    void breakingTheDraftThenSubmittingIs422WithDesignerShapedIssues() throws Exception {
        mockMvc.perform(put("/api/v1/journeys/pl-express/versions/1")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BROKEN_CONFIG_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("draft")));

        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/1/validate")
                        .header("X-Registry-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issues[0].code", is("danglingEdge")))
                .andExpect(jsonPath("$.issues[0].severity", is("error")))
                .andExpect(jsonPath("$.issues[0].nodeId", is("a")));

        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/1/submit")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.issues", hasSize(1)));
    }

    @Test
    @Order(13)
    void fixedDraftSubmits() throws Exception {
        mockMvc.perform(put("/api/v1/journeys/pl-express/versions/1")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CONFIG_BODY))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/1/submit")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("pendingApproval")));
    }

    @Test
    @Order(14)
    void theAuthorMayNotApproveTheirOwnVersion403() throws Exception {
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/1/approve")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", is("FORBIDDEN")));
    }

    @Test
    @Order(15)
    void aDifferentCheckerApprovesAndTheJourneyGoesLive() throws Exception {
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/1/approve")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", CHECKER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("published")))
                .andExpect(jsonPath("$.approverId", is(CHECKER)));

        mockMvc.perform(get("/api/v1/journeys/pl-express").header("X-Registry-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion", is(1)))
                .andExpect(jsonPath("$.versions[0].status", is("published")));
    }

    // ---- engine-facing reads ---------------------------------------------------------

    @Test
    @Order(20)
    void theEngineReadsThePublishedConfig() throws Exception {
        mockMvc.perform(get("/api/v1/published-journeys").header("X-Registry-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].journeyKey", is("pl-express")))
                .andExpect(jsonPath("$[0].version", is(1)))
                .andExpect(jsonPath("$[0].config.startNodeId", is("n_start")));

        mockMvc.perform(get("/api/v1/published-journeys/pl-express/versions/1")
                        .header("X-Registry-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config.nodes", notNullValue()));
    }

    @Test
    @Order(21)
    void rejectedAndUnknownReadsMapToConflictAndNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/journeys/no-such-journey").header("X-Registry-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("NOT_FOUND")));

        // v2 draft -> submit -> reject with a comment; pointer stays on v1.
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_CONFIG_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version", is(2)));
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/2/submit")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", MAKER))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/journeys/pl-express/versions/2/reject")
                        .header("X-Registry-Token", TOKEN).header("X-User-Id", CHECKER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\": \"branch coverage is too thin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("rejected")))
                .andExpect(jsonPath("$.note", is("branch coverage is too thin")));

        mockMvc.perform(get("/api/v1/journeys/pl-express").header("X-Registry-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersion", is(1)));

        // The rejected version is NEVER served to the engine.
        mockMvc.perform(get("/api/v1/published-journeys/pl-express/versions/2")
                        .header("X-Registry-Token", TOKEN))
                .andExpect(status().isNotFound());
    }
}

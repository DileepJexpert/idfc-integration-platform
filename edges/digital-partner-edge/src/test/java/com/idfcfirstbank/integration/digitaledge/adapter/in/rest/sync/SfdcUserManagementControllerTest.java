package com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync;

import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.BearerTokenValidator;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncInvocable;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Edge-level contract for the SFDC user-management door: FAIL CLOSED on a missing/bad
 * Bearer (401 before the capability is touched), 400 on a missing svcName/orgName, a
 * fail-closed routing error surfaced as 422, and a downstream technical failure as 502.
 * The controller runs over the REAL {@link SyncCapabilityInvoker} + a stub capability so
 * the wiring (dispatch by capabilityKey, error mapping) is exercised end-to-end.
 */
class SfdcUserManagementControllerTest {

    private static final String URL = "/api/v1/sfdcUserManagement";
    private static final String GOOD = "Bearer good-token";

    /** Mutable stub capability: returns {@link #response} or throws {@link #toThrow}. */
    static final class StubCapability implements SyncInvocable {
        Map<String, Object> response = Map.of("org", "ORG_A");
        RuntimeException toThrow;

        @Override
        public String capabilityKey() {
            return "sfdc-user-management";
        }

        @Override
        public Map<String, Object> invoke(String operation, Map<String, Object> payload, SyncRequestContext context) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private StubCapability capability;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        capability = new StubCapability();
        SyncCapabilityInvoker invoker = new SyncCapabilityInvoker(List.of(capability));
        BearerTokenValidator bearer = auth -> GOOD.equals(auth);   // fail-closed stub
        mvc = MockMvcBuilders.standaloneSetup(new SfdcUserManagementController(invoker, bearer)).build();
    }

    private static String body(String svcName, String orgName) {
        return "{\"svcName\":\"" + svcName + "\",\"orgName\":\"" + orgName + "\",\"payload\":{\"crn\":\"C1\"}}";
    }

    @Test
    void missingBearerIsUnauthorized() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_FETCH", "ORG_A")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void wrongBearerIsUnauthorized() throws Exception {
        mvc.perform(post(URL).header("Authorization", "Bearer nope")
                        .contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_FETCH", "ORG_A")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingSvcNameIsBadRequest() throws Exception {
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orgName\":\"ORG_A\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingOrgNameIsBadRequest() throws Exception {
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"svcName\":\"SFDC_USER_FETCH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizedReadReturnsTheBody() throws Exception {
        capability.response = Map.of("org", "ORG_A", "totalSize", 1);
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_FETCH", "ORG_A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org").value("ORG_A"));
    }

    @Test
    void failClosedRoutingErrorIs422() throws Exception {
        capability.toThrow = new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_ORG", "no org");
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_FETCH", "ORG_ZZ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_ORG"));
    }

    @Test
    void missingIdempotencyKeyOnAWriteIs422() throws Exception {
        capability.toThrow = new SyncTechnicalException(ErrorClass.PERMANENT, "MISSING_IDEMPOTENCY_KEY", "need a key");
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_CREATE", "ORG_A")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));
    }

    @Test
    void downstreamTechnicalFailureIs502() throws Exception {
        capability.toThrow = new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "unreachable");
        mvc.perform(post(URL).header("Authorization", GOOD)
                        .contentType(MediaType.APPLICATION_JSON).content(body("SFDC_USER_FETCH", "ORG_A")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("IO"));
    }
}

package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency.InMemorySfdcIdempotencyStore;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperRegistry;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcOrgPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncInvocation;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Slice 2 — WRITE idempotency + retry safety (the imps money-movement discipline applied
 * to identity writes):
 * <ul>
 *   <li>same key -&gt; ONE execution, the second returns the prior result;</li>
 *   <li>a DEFINITIVE outcome (success OR a business rejection) is cached; a TECHNICAL
 *       failure is NOT cached, so a retry re-executes (safe only under the key);</li>
 *   <li>a business "no" (SFDC {@code success:false}) is a BUSINESS outcome, never a
 *       technical error; a write timeout is AMBIGUOUS;</li>
 *   <li>a write with no idempotencyKey is refused before SFDC is touched.</li>
 * </ul>
 */
class SfdcWriteIdempotencyTest {

    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "JMI");

    /** Scriptable port: counts calls, returns {@link #response} or throws {@link #toThrow}. */
    static final class ScriptedPort implements SfdcOrgPort {
        int calls;
        Map<String, Object> response = Map.of("success", true, "id", "005CREATED");
        RuntimeException toThrow;

        @Override
        public Map<String, Object> call(ResolvedSfdcTarget target, Map<String, Object> requestBody) {
            calls++;
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private final ScriptedPort port = new ScriptedPort();
    private final SfdcUserManagementService service = new SfdcUserManagementService(
            new SfdcOrgRouteResolver(props()), new SfdcMapperRegistry(), port, new InMemorySfdcIdempotencyStore());
    private final List<SyncInvocation> recorded = new ArrayList<>();
    private final SyncCapabilityInvoker invoker = new SyncCapabilityInvoker(List.of(service), recorded::add);

    private static SfdcUserManagementProperties props() {
        return new SfdcUserManagementProperties(2000, 3000,
                List.of(new SfdcUserManagementProperties.Route("SFDC_USER_CREATE", "/svc/create", true)),
                List.of(new SfdcUserManagementProperties.Org("ORG_A", "http://a.local", "NONE", null, true)));
    }

    private static Map<String, Object> writeBody(String key, String username) {
        return Map.of("svcName", "SFDC_USER_CREATE", "orgName", "ORG_A",
                "idempotencyKey", key, "payload", Map.of("Username", username));
    }

    @Test
    void sameKeyExecutesOnceAndReplaysThePriorResult() {
        Map<String, Object> first = service.invoke("SFDC_USER_CREATE", writeBody("k1", "u@a"), CTX);
        Map<String, Object> second = service.invoke("SFDC_USER_CREATE", writeBody("k1", "u@a"), CTX);
        assertThat(port.calls).isEqualTo(1);          // SFDC called exactly once
        assertThat(second).isEqualTo(first);          // replay returns the prior result
    }

    @Test
    void technicalFailureIsNotCachedSoARetryReExecutes() {
        port.toThrow = new SyncTechnicalException(ErrorClass.TRANSIENT, "HTTP_503", "backend");
        assertThatThrownBy(() -> service.invoke("SFDC_USER_CREATE", writeBody("k2", "u@a"), CTX))
                .isInstanceOf(SyncTechnicalException.class);
        assertThat(port.calls).isEqualTo(1);
        // same key again: because the 5xx was NOT cached, the write re-executes (no blind
        // dedup of a maybe-not-applied write) — the key is what makes that retry safe.
        assertThatThrownBy(() -> service.invoke("SFDC_USER_CREATE", writeBody("k2", "u@a"), CTX))
                .isInstanceOf(SyncTechnicalException.class);
        assertThat(port.calls).isEqualTo(2);
    }

    @Test
    void businessRejectionIsCachedAndReplayed() {
        port.response = Map.of("success", false,
                "errors", List.of(Map.of("statusCode", "DUPLICATE_USERNAME")));
        Map<String, Object> first = service.invoke("SFDC_USER_CREATE", writeBody("k3", "DUPE_USER"), CTX);
        assertThat(first).containsEntry("success", false);
        Map<String, Object> second = service.invoke("SFDC_USER_CREATE", writeBody("k3", "DUPE_USER"), CTX);
        assertThat(port.calls).isEqualTo(1);          // a business "no" is DEFINITIVE — cached, not re-run
        assertThat(second).isEqualTo(first);
    }

    @Test
    void businessRejectionIsAuditedAsBusinessNotTechnical() {
        port.response = Map.of("success", false, "errors", List.of(Map.of("statusCode", "DUPLICATE_USERNAME")));
        invoker.invoke("sfdc-user-management", "SFDC_USER_CREATE", writeBody("k4", "DUPE_USER"), CTX);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).outcome()).isEqualTo(SyncOutcome.BUSINESS_FAILURE);
        assertThat(recorded.get(0).errorCode()).isNull();      // NOT a technical error
    }

    @Test
    void writeTimeoutIsAmbiguous() {
        port.toThrow = new SyncTechnicalException(ErrorClass.AMBIGUOUS, "READ_TIMEOUT", "timeout");
        assertThatThrownBy(() -> service.invoke("SFDC_USER_CREATE", writeBody("k5", "u@a"), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.errorClass()).isEqualTo(ErrorClass.AMBIGUOUS));
    }

    @Test
    void writeWithoutIdempotencyKeyIsRefusedBeforeTouchingSfdc() {
        Map<String, Object> noKey = Map.of("svcName", "SFDC_USER_CREATE", "orgName", "ORG_A",
                "payload", Map.of("Username", "u@a"));
        assertThatThrownBy(() -> service.invoke("SFDC_USER_CREATE", noKey, CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class, e -> {
                    assertThat(e.code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
                    assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT);
                });
        assertThat(port.calls).isZero();
    }
}

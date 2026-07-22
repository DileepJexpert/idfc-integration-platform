package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

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
 * Business-vs-technical separation, recorded through the REAL {@link SyncCapabilityInvoker}
 * exactly as the digital edge wires it: a good read is one SUCCESS audit row; a downstream
 * transport failure and a fail-closed routing error are both TECHNICAL_ERROR rows carrying
 * the class + code (never mislabelled as a business outcome), and the technical exception
 * still propagates to the caller.
 */
class SfdcSyncAuditTest {

    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "JMI");

    static final class StubPort implements SfdcOrgPort {
        Map<String, Object> response = Map.of("org", "ORG_A");
        RuntimeException toThrow;

        @Override
        public Map<String, Object> call(ResolvedSfdcTarget target, Map<String, Object> requestBody) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private final StubPort port = new StubPort();
    private final SfdcUserManagementService service = new SfdcUserManagementService(
            new SfdcOrgRouteResolver(props()), new SfdcMapperRegistry(), port,
            new com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency.InMemorySfdcIdempotencyStore());
    private final List<SyncInvocation> recorded = new ArrayList<>();
    private final SyncCapabilityInvoker invoker = new SyncCapabilityInvoker(List.of(service), recorded::add);

    private static SfdcUserManagementProperties props() {
        return new SfdcUserManagementProperties(3000, 10000,
                List.of(new SfdcUserManagementProperties.Route("SFDC_USER_FETCH", "/svc/fetch", false)),
                List.of(new SfdcUserManagementProperties.Org("ORG_A", "http://a.local", "NONE", null, true)));
    }

    private static Map<String, Object> body(String org) {
        return Map.of("svcName", "SFDC_USER_FETCH", "orgName", org, "payload", Map.of("crn", "C1"));
    }

    @Test
    void successfulReadWritesOneSuccessAuditRow() {
        invoker.invoke("sfdc-user-management", "SFDC_USER_FETCH", body("ORG_A"), CTX);
        assertThat(recorded).hasSize(1);
        SyncInvocation rec = recorded.get(0);
        assertThat(rec.capabilityKey()).isEqualTo("sfdc-user-management");
        assertThat(rec.operation()).isEqualTo("SFDC_USER_FETCH");
        assertThat(rec.source()).isEqualTo("JMI");
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.SUCCESS);
        assertThat(rec.errorCode()).isNull();
    }

    @Test
    void transportFailureIsAuditedAsTechnicalAndRethrown() {
        port.toThrow = new SyncTechnicalException(ErrorClass.TRANSIENT, "IO", "SFDC org unreachable");
        assertThatThrownBy(() -> invoker.invoke("sfdc-user-management", "SFDC_USER_FETCH", body("ORG_A"), CTX))
                .isInstanceOf(SyncTechnicalException.class);
        assertThat(recorded).hasSize(1);
        SyncInvocation rec = recorded.get(0);
        assertThat(rec.outcome()).isEqualTo(SyncOutcome.TECHNICAL_ERROR);
        assertThat(rec.errorClass()).isEqualTo("TRANSIENT");
        assertThat(rec.errorCode()).isEqualTo("IO");
    }

    @Test
    void failClosedUnknownOrgIsAuditedAsTechnicalWithItsCode() {
        assertThatThrownBy(() -> invoker.invoke("sfdc-user-management", "SFDC_USER_FETCH", body("ORG_ZZ"), CTX))
                .isInstanceOf(SyncTechnicalException.class);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).outcome()).isEqualTo(SyncOutcome.TECHNICAL_ERROR);
        assertThat(recorded.get(0).errorCode()).isEqualTo("UNKNOWN_ORG");
    }
}

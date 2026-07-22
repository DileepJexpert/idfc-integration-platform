package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.adapter.out.idempotency.InMemorySfdcIdempotencyStore;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperRegistry;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcOrgPort;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service routes on (svcName, orgName): the SAME svcName goes to a DIFFERENT SFDC
 * host purely by org — the org-routing proof, captured deterministically by a fake port.
 * Control fields are stripped before the payload is sent downstream; unknown org/svcName
 * fail closed; a write svcName is refused in slice 1.
 */
class SfdcUserManagementServiceTest {

    private static final String PATH = "/services/apexrest/usermgmt/user/fetch";
    private static final SyncRequestContext CTX = SyncRequestContext.of("corr-1", "txn-1", "JMI");

    /** Captures the resolved target + downstream body; returns a canned response. */
    static final class CapturingPort implements SfdcOrgPort {
        ResolvedSfdcTarget lastTarget;
        Map<String, Object> lastBody;
        Map<String, Object> response = Map.of("org", "stub");
        RuntimeException toThrow;

        @Override
        public Map<String, Object> call(ResolvedSfdcTarget target, Map<String, Object> requestBody) {
            this.lastTarget = target;
            this.lastBody = requestBody;
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private final CapturingPort port = new CapturingPort();
    private final SfdcUserManagementService service = new SfdcUserManagementService(
            new SfdcOrgRouteResolver(props()), new SfdcMapperRegistry(), port, new InMemorySfdcIdempotencyStore());

    private static SfdcUserManagementProperties props() {
        return new SfdcUserManagementProperties(3000, 10000,
                List.of(new SfdcUserManagementProperties.Route("SFDC_USER_FETCH", PATH, false),
                        new SfdcUserManagementProperties.Route("SFDC_USER_CREATE", "/svc/create", true)),
                List.of(new SfdcUserManagementProperties.Org("ORG_A", "http://a.local:19112", "BEARER", "tok-a", true),
                        new SfdcUserManagementProperties.Org("ORG_B", "http://b.local:19113", "BEARER", "tok-b", true)));
    }

    private static Map<String, Object> body(String org, Map<String, Object> payload) {
        return Map.of("svcName", "SFDC_USER_FETCH", "orgName", org, "payload", payload);
    }

    @Test
    void orgAReadResolvesToOrgAHost() {
        service.invoke("SFDC_USER_FETCH", body("ORG_A", Map.of("crn", "C1")), CTX);
        assertThat(port.lastTarget.orgName()).isEqualTo("ORG_A");
        assertThat(port.lastTarget.url()).isEqualTo("http://a.local:19112" + PATH);
    }

    @Test
    void sameSvcNameOrgBResolvesToOrgBHost() {
        service.invoke("SFDC_USER_FETCH", body("ORG_B", Map.of("crn", "C1")), CTX);
        assertThat(port.lastTarget.orgName()).isEqualTo("ORG_B");
        assertThat(port.lastTarget.url()).isEqualTo("http://b.local:19113" + PATH);
    }

    @Test
    void controlFieldsAreStrippedAndNestedPayloadIsSentDownstream() {
        service.invoke("SFDC_USER_FETCH", body("ORG_A", Map.of("crn", "C1", "recordType", "User")), CTX);
        assertThat(port.lastBody).isEqualTo(Map.of("crn", "C1", "recordType", "User"));
        assertThat(port.lastBody).doesNotContainKeys("svcName", "orgName");
    }

    @Test
    void returnsTheMappedResponseBody() {
        port.response = Map.of("org", "ORG_A", "totalSize", 1);
        Map<String, Object> out = service.invoke("SFDC_USER_FETCH", body("ORG_A", Map.of()), CTX);
        assertThat(out).containsEntry("org", "ORG_A").containsEntry("totalSize", 1);
    }

    @Test
    void unknownOrgFailsClosed() {
        assertThatThrownBy(() -> service.invoke("SFDC_USER_FETCH", body("ORG_ZZ", Map.of()), CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.code()).isEqualTo("UNKNOWN_ORG"));
    }

    @Test
    void unknownSvcNameFailsClosed() {
        Map<String, Object> b = Map.of("svcName", "NOPE", "orgName", "ORG_A", "payload", Map.of());
        assertThatThrownBy(() -> service.invoke("NOPE", b, CTX))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.code()).isEqualTo("NO_ROUTE"));
    }

    @Test
    void writeAlsoRoutesByOrg() {
        // org-routing applies to writes too: same svcName, different org -> different host
        Map<String, Object> b = Map.of("svcName", "SFDC_USER_CREATE", "orgName", "ORG_B",
                "idempotencyKey", "k-1", "payload", Map.of("Username", "u@b"));
        service.invoke("SFDC_USER_CREATE", b, CTX);
        assertThat(port.lastTarget.orgName()).isEqualTo("ORG_B");
        assertThat(port.lastTarget.url()).isEqualTo("http://b.local:19113/svc/create");
        assertThat(port.lastTarget.write()).isTrue();
    }
}

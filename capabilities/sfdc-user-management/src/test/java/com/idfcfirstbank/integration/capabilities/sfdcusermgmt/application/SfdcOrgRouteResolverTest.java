package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.config.SfdcUserManagementProperties;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.SfdcAuthType;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The org-routing unit: svcName picks the PATH, orgName picks the HOST, and the two
 * compose. Unknown/disabled org and unknown svcName all fail closed (PERMANENT) — there
 * is no default org.
 */
class SfdcOrgRouteResolverTest {

    private static final String PATH = "/services/apexrest/usermgmt/user/fetch";

    private static SfdcUserManagementProperties props() {
        return new SfdcUserManagementProperties(3000, 10000,
                List.of(new SfdcUserManagementProperties.Route("SFDC_USER_FETCH", PATH, false),
                        new SfdcUserManagementProperties.Route("SFDC_USER_CREATE", "/services/apexrest/usermgmt/user/create", true)),
                List.of(new SfdcUserManagementProperties.Org("ORG_A", "http://a.local:19112", "BEARER", "tok-a", true),
                        new SfdcUserManagementProperties.Org("ORG_B", "http://b.local:19113/", "BEARER", "tok-b", true),
                        new SfdcUserManagementProperties.Org("ORG_OFF", "http://off.local", "NONE", null, false)));
    }

    private final SfdcOrgRouteResolver resolver = new SfdcOrgRouteResolver(props());

    @Test
    void composesOrgHostWithSvcNamePath() {
        ResolvedSfdcTarget a = resolver.resolve("SFDC_USER_FETCH", "ORG_A");
        assertThat(a.url()).isEqualTo("http://a.local:19112" + PATH);
        assertThat(a.authType()).isEqualTo(SfdcAuthType.BEARER);
        assertThat(a.authToken()).isEqualTo("tok-a");
        assertThat(a.write()).isFalse();
    }

    @Test
    void sameSvcNameDifferentOrgResolvesDifferentHost() {
        assertThat(resolver.resolve("SFDC_USER_FETCH", "ORG_A").url()).contains("a.local");
        // trailing slash on baseUrl is normalised so the path is not doubled
        assertThat(resolver.resolve("SFDC_USER_FETCH", "ORG_B").url()).isEqualTo("http://b.local:19113" + PATH);
    }

    @Test
    void unknownSvcNameFailsClosedNoRoute() {
        assertThatThrownBy(() -> resolver.resolve("NOPE", "ORG_A"))
                .isInstanceOfSatisfying(SyncTechnicalException.class, e -> {
                    assertThat(e.code()).isEqualTo("NO_ROUTE");
                    assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT);
                });
    }

    @Test
    void unknownOrgFailsClosedNoDefaultOrg() {
        assertThatThrownBy(() -> resolver.resolve("SFDC_USER_FETCH", "ORG_ZZZ"))
                .isInstanceOfSatisfying(SyncTechnicalException.class, e -> {
                    assertThat(e.code()).isEqualTo("UNKNOWN_ORG");
                    assertThat(e.errorClass()).isEqualTo(ErrorClass.PERMANENT);
                });
    }

    @Test
    void missingOrgNameFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve("SFDC_USER_FETCH", null))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.code()).isEqualTo("UNKNOWN_ORG"));
    }

    @Test
    void disabledOrgFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve("SFDC_USER_FETCH", "ORG_OFF"))
                .isInstanceOfSatisfying(SyncTechnicalException.class,
                        e -> assertThat(e.code()).isEqualTo("ORG_DISABLED"));
    }

    @Test
    void writeFlagIsCarriedThrough() {
        assertThat(resolver.resolve("SFDC_USER_CREATE", "ORG_A").write()).isTrue();
    }
}

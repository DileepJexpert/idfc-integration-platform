package com.idfcfirstbank.integration.platform.routeconfig;

import com.idfcfirstbank.integration.platform.routeconfig.adapter.out.store.InMemoryRouteConfigStore;
import com.idfcfirstbank.integration.platform.routeconfig.application.RouteConfigService;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterEndpoint;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteConfigServiceTest {

    private RouteConfigService service() {
        return new RouteConfigService(new InMemoryRouteConfigStore());
    }

    private static CrmApiRouterEndpoint endpoint(String svc, String transport) {
        return new CrmApiRouterEndpoint(0, svc, "v1", "host", 443, "/base", "/path",
                null, "c", "auth", transport, "enc", "topic", "scope");
    }

    @Test
    void createAssignsSnoAndDateModified() {
        CrmApiRouterEndpoint e = service().createEndpoint(endpoint("posidex", "HTTP"));
        assertThat(e.sno()).isEqualTo(1);
        assertThat(e.dateModified()).isNotNull();
    }

    @Test
    void duplicateSvcNamePlusTransportIsRejected() {
        RouteConfigService s = service();
        s.createEndpoint(endpoint("posidex", "HTTP"));
        assertThatThrownBy(() -> s.createEndpoint(endpoint("posidex", "HTTP")))
                .isInstanceOf(RouteConfigService.DuplicateConfigException.class);
        // same svcName, different transport is allowed
        assertThat(s.createEndpoint(endpoint("posidex", "KAFKA")).sno()).isEqualTo(2);
    }

    @Test
    void listIsSortedBySno() {
        RouteConfigService s = service();
        s.createEndpoint(endpoint("a", "HTTP"));
        s.createEndpoint(endpoint("b", "HTTP"));
        assertThat(s.listEndpoints()).extracting(CrmApiRouterEndpoint::sno).containsExactly(1L, 2L);
    }

    @Test
    void gatewayCreateAndDelete() {
        RouteConfigService s = service();
        CrmApiRouterGateway g = s.createGateway(new CrmApiRouterGateway(0, "posidex", "HTTP"));
        assertThat(s.listGateways()).hasSize(1);
        assertThat(s.delete("crm-api-router-gateway", g.sno())).isTrue();
        assertThat(s.listGateways()).isEmpty();
    }
}

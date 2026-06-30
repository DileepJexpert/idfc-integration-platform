package com.idfcfirstbank.integration.platform.routeconfig.application;

import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterEndpoint;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterGateway;
import com.idfcfirstbank.integration.platform.routeconfig.domain.port.RouteConfigStorePort;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Registry CRUD (BRD §7): create with duplicate validation (svcName+transport),
 * auto-{@code sno} via the store's atomic counter, {@code dateModified} on create,
 * results sorted by {@code sno}. Config data — not transactional, no idempotency
 * needed (per the profile).
 */
@Service
public class RouteConfigService {

    private final RouteConfigStorePort store;

    public RouteConfigService(RouteConfigStorePort store) {
        this.store = store;
    }

    public CrmApiRouterEndpoint createEndpoint(CrmApiRouterEndpoint in) {
        if (store.endpoints().stream().anyMatch(e ->
                eq(e.svcName(), in.svcName()) && eq(e.transport(), in.transport()))) {
            throw new DuplicateConfigException(
                    "endpoint already exists for svcName=" + in.svcName() + " transport=" + in.transport());
        }
        CrmApiRouterEndpoint stored = new CrmApiRouterEndpoint(
                store.nextSno(), in.svcName(), in.version(), in.endpointHost(), in.endpointPort(),
                in.endpointBasePath(), in.endpointPath(), Instant.now().toString(), in.comments(),
                in.authorization(), in.transport(), in.encSource(), in.responseTopic(), in.scope());
        store.putEndpoint(stored);
        return stored;
    }

    public List<CrmApiRouterEndpoint> bulkCreateEndpoints(List<CrmApiRouterEndpoint> ins) {
        return ins.stream().map(this::createEndpoint).toList();
    }

    public List<CrmApiRouterEndpoint> listEndpoints() {
        return store.endpoints().stream()
                .sorted(Comparator.comparingLong(CrmApiRouterEndpoint::sno)).toList();
    }

    public CrmApiRouterGateway createGateway(CrmApiRouterGateway in) {
        if (store.gateways().stream().anyMatch(g ->
                eq(g.svcName(), in.svcName()) && eq(g.transport(), in.transport()))) {
            throw new DuplicateConfigException(
                    "gateway already exists for svcName=" + in.svcName() + " transport=" + in.transport());
        }
        CrmApiRouterGateway stored = new CrmApiRouterGateway(store.nextSno(), in.svcName(), in.transport());
        store.putGateway(stored);
        return stored;
    }

    public List<CrmApiRouterGateway> listGateways() {
        return store.gateways().stream()
                .sorted(Comparator.comparingLong(CrmApiRouterGateway::sno)).toList();
    }

    /** Delete by set + sno; XSS-escape the set input (delete inputs are echoed in errors). */
    public boolean delete(String set, long sno) {
        return store.delete(escape(set), sno);
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equals(b);
    }

    private static String escape(String s) {
        return s == null ? null : s.replace("<", "&lt;").replace(">", "&gt;");
    }

    public static class DuplicateConfigException extends RuntimeException {
        public DuplicateConfigException(String message) { super(message); }
    }
}

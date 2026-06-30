package com.idfcfirstbank.integration.platform.routeconfig.adapter.out.store;

import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterEndpoint;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterGateway;
import com.idfcfirstbank.integration.platform.routeconfig.domain.port.RouteConfigStorePort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory store mocking the Aerospike sets + atomic sno counter. */
@Component
public class InMemoryRouteConfigStore implements RouteConfigStorePort {

    private final AtomicLong sno = new AtomicLong();
    private final ConcurrentHashMap<Long, CrmApiRouterEndpoint> endpoints = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CrmApiRouterGateway> gateways = new ConcurrentHashMap<>();

    @Override public long nextSno() { return sno.incrementAndGet(); }
    @Override public void putEndpoint(CrmApiRouterEndpoint e) { endpoints.put(e.sno(), e); }
    @Override public List<CrmApiRouterEndpoint> endpoints() { return List.copyOf(endpoints.values()); }
    @Override public void putGateway(CrmApiRouterGateway g) { gateways.put(g.sno(), g); }
    @Override public List<CrmApiRouterGateway> gateways() { return List.copyOf(gateways.values()); }

    @Override
    public boolean delete(String set, long sno) {
        if ("crm-api-router-gateway".equals(set)) {
            return gateways.remove(sno) != null;
        }
        return endpoints.remove(sno) != null;
    }
}

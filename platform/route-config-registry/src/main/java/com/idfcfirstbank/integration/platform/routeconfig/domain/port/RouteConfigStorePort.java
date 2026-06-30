package com.idfcfirstbank.integration.platform.routeconfig.domain.port;

import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterEndpoint;
import com.idfcfirstbank.integration.platform.routeconfig.domain.CrmApiRouterGateway;

import java.util.List;

/** The config store (Aerospike sets crm-api-router-endpoint / -gateway; mocked
 * in-memory locally). {@code nextSno} is the atomic counter the registry uses. */
public interface RouteConfigStorePort {
    long nextSno();
    void putEndpoint(CrmApiRouterEndpoint endpoint);
    List<CrmApiRouterEndpoint> endpoints();
    void putGateway(CrmApiRouterGateway gateway);
    List<CrmApiRouterGateway> gateways();
    boolean delete(String set, long sno);
}

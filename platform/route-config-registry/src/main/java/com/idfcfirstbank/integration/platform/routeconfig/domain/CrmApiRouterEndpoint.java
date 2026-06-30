package com.idfcfirstbank.integration.platform.routeconfig.domain;

/** API-router endpoint config (BRD §7), Aerospike set {@code crm-api-router-endpoint}. */
public record CrmApiRouterEndpoint(
        long sno,
        String svcName,
        String version,
        String endpointHost,
        Integer endpointPort,
        String endpointBasePath,
        String endpointPath,
        String dateModified,
        String comments,
        String authorization,
        String transport,
        String encSource,
        String responseTopic,
        String scope) {
}

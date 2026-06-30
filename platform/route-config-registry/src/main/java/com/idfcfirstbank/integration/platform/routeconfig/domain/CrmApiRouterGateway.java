package com.idfcfirstbank.integration.platform.routeconfig.domain;

/** API-router gateway config (BRD §7), Aerospike set {@code crm-api-router-gateway}. */
public record CrmApiRouterGateway(long sno, String svcName, String transport) {
}

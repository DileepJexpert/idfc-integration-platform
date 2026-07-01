package com.idfcfirstbank.integration.capabilities.verification.domain.model;

/**
 * A downstream target resolved from the CONTROL PLANE (our route registry) by
 * svcName — never from the inbound message. {@code baseUrl} has passed allow-list
 * validation before this record is handed to an adapter.
 */
public record ResolvedRoute(String svcName, String baseUrl, AuthType authType) {
}

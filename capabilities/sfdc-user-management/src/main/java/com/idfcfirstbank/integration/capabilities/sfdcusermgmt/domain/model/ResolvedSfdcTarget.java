package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model;

/**
 * A fully-resolved SFDC call target: the {@code url} is {@code org.baseUrl + route.path}
 * — the COMPOSITION of the org table (host, chosen by the request's org name) and the
 * route table (path, chosen by svcName). Both came from OUR config; the inbound message
 * never supplied a URL. By the time an adapter receives this record the org has passed
 * the allow-list (it has a row and is enabled) and the path has passed the no-host-
 * override guard, so {@code url} is safe to call.
 *
 * @param write true if this svcName is a state-changing operation (idempotency-guarded,
 *              slice 2); false for a read.
 */
public record ResolvedSfdcTarget(
        String svcName,
        String orgName,
        String url,
        SfdcAuthType authType,
        String authToken,
        boolean write) {
}

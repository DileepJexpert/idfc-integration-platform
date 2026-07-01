package com.idfcfirstbank.integration.capabilities.verification.domain.port.out;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;

import java.util.Map;

/**
 * A per-svcName downstream adapter (Karza vehicle-RC, IMPS prefetch, ... — all mocked
 * via WireMock). Selected by {@link #svcName()}. Receives the MAPPED request + the
 * control-plane-resolved route, calls the downstream, returns its raw response map.
 * Throws {@code VerificationException} (TRANSIENT/PERMANENT) on failure.
 */
public interface VerificationAdapter {
    String svcName();
    Map<String, Object> call(ResolvedRoute route, Map<String, Object> mappedRequest);
}

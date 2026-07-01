package com.idfcfirstbank.integration.capabilities.verification.domain.port.out;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;

/**
 * CONTROL-PLANE endpoint resolution (correction #1): resolve svcName -> endpoint/auth
 * from OUR registry, and validate the target against an allow-list. The inbound
 * message never supplies the endpoint (kills the wrapper's SSRF shape). The real
 * implementation reads the route-config registry (D#2); step 1 is config-as-data.
 */
public interface RouteResolverPort {
    ResolvedRoute resolve(String svcName);   // throws VerificationException(PERMANENT) if unknown or not allow-listed
}

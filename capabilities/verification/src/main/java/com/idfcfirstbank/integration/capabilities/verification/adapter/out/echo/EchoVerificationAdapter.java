package com.idfcfirstbank.integration.capabilities.verification.adapter.out.echo;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The trivial ECHO adapter — step-1 proof that the shell works end-to-end (control-plane
 * resolve -> allow-list -> adapter select -> passthrough mapper -> envelope) WITHOUT any
 * real downstream. It echoes the mapped request back and reports the resolved endpoint,
 * proving the endpoint came from the control plane, not the message. Karza/IMPS adapters
 * (steps 2-3) replace this pattern with real WireMock calls.
 */
@Component
public class EchoVerificationAdapter implements VerificationAdapter {

    @Override
    public String svcName() {
        return "ECHO";
    }

    @Override
    public Map<String, Object> call(ResolvedRoute route, Map<String, Object> mappedRequest) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("echoed", mappedRequest == null ? Map.of() : mappedRequest);
        out.put("resolvedEndpoint", route.baseUrl());   // proves control-plane resolution
        return out;
    }
}

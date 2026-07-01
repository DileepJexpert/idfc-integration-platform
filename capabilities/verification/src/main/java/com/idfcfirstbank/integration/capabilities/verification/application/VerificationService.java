package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.RouteResolverPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One verification: resolve the endpoint from the CONTROL PLANE by svcName (+ allow-list),
 * select the adapter + mapper pair by svcName, map request -> call downstream -> map
 * response, and return the universal {@code {ISSUCCESS, DATA}} envelope. NO business
 * logic beyond the mapping; NO endpoint ever trusted from the message. Failures throw
 * {@code VerificationException} (classified) for the dispatcher's retry/DLQ.
 */
@Service
public class VerificationService {

    private final RouteResolverPort routeResolver;
    private final AdapterRegistry adapters;
    private final MapperRegistry mappers;

    public VerificationService(RouteResolverPort routeResolver, AdapterRegistry adapters, MapperRegistry mappers) {
        this.routeResolver = routeResolver;
        this.adapters = adapters;
        this.mappers = mappers;
    }

    /** Run svcName over the given request payload; returns the ISSUCCESS/DATA envelope. */
    public Map<String, Object> verify(String svcName, Map<String, Object> request) {
        ResolvedRoute route = routeResolver.resolve(svcName);     // control-plane + allow-list (throws if not permitted)
        VerificationAdapter adapter = adapters.forSvcName(svcName);
        MapperPair mapperPair = mappers.forSvcName(svcName);

        Map<String, Object> mappedRequest = mapperPair.request().map(request);
        Map<String, Object> downstream = adapter.call(route, mappedRequest);   // WireMock (mocked)
        Map<String, Object> data = mapperPair.response().map(downstream);
        return VerificationEnvelope.success(data);
    }
}

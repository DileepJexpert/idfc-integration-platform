package com.idfcfirstbank.integration.capabilities.verification.adapter.out.route;

import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.AuthType;
import com.idfcfirstbank.integration.capabilities.verification.domain.model.ResolvedRoute;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.RouteResolverPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Config-as-data control-plane resolver (correction #1): svcName -> endpoint/auth comes
 * from OUR config, and the resolved host is validated against the allow-list before any
 * call. The inbound message NEVER supplies the endpoint. The real route-config-registry
 * integration (D#2) swaps in behind this same port with no caller change.
 */
@Component
public class ConfigRouteResolver implements RouteResolverPort {

    private final Map<String, VerificationProperties.Route> routesBySvcName = new HashMap<>();
    private final Set<String> allowedHosts;

    public ConfigRouteResolver(VerificationProperties properties) {
        for (VerificationProperties.Route r : properties.routes()) {
            routesBySvcName.put(r.svcName(), r);
        }
        this.allowedHosts = Set.copyOf(properties.allowedHosts());
    }

    @Override
    public ResolvedRoute resolve(String svcName) {
        VerificationProperties.Route route = routesBySvcName.get(svcName);
        if (route == null) {
            throw new VerificationException(ErrorClass.PERMANENT, "NO_ROUTE",
                    "no control-plane route registered for svcName=" + svcName);
        }
        String host = hostOf(route.baseUrl());
        if (host == null || !allowedHosts.contains(host)) {
            // Anti-SSRF: refuse any target we did not register in the allow-list.
            throw new VerificationException(ErrorClass.PERMANENT, "TARGET_NOT_ALLOWED",
                    "resolved target host is not allow-listed for svcName=" + svcName);
        }
        return new ResolvedRoute(svcName, route.baseUrl(), authType(route.authType()));
    }

    private static String hostOf(String baseUrl) {
        try {
            return URI.create(baseUrl).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static AuthType authType(String raw) {
        try {
            return raw == null ? AuthType.NONE : AuthType.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return AuthType.NONE;
        }
    }
}

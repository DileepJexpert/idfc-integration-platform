package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock;

import com.idfcfirstbank.integration.edges.sfdcingress.config.EdgeProperties;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import org.springframework.stereotype.Component;

/**
 * Mock two-token auth (real Hydra + Kong is a later slice). Accepts a single
 * configured token ({@code idfc.edge.auth.expected-token}); the secret is an
 * adapter/config concern, never the domain's.
 */
@Component
public class MockAuthTokenAdapter implements AuthTokenPort {

    private final String expectedToken;

    public MockAuthTokenAdapter(EdgeProperties properties) {
        this.expectedToken = properties.auth() == null ? null : properties.auth().expectedToken();
    }

    @Override
    public boolean authenticate(String presentedToken) {
        return expectedToken != null && expectedToken.equals(presentedToken);
    }
}

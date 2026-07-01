package com.idfcfirstbank.integration.capabilities.verification.adapter.out.auth;

import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.TokenProviderPort;
import org.springframework.stereotype.Component;

/** Mock OAuth token (Karza). Real token endpoint + creds arrive from vault (D#1). */
@Component
public class MockOAuthTokenProvider implements TokenProviderPort {
    @Override
    public String bearerToken(String svcName) {
        return "mock-oauth-token-for-" + svcName;
    }
}

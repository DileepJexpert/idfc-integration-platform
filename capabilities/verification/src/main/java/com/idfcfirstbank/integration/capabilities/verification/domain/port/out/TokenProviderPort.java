package com.idfcfirstbank.integration.capabilities.verification.domain.port.out;

/** OAuth/BASIC credential source per svcName. Real creds come from vault (open input
 *  D#1); the mock returns a fixed token so the slice runs Docker-free. */
public interface TokenProviderPort {
    String bearerToken(String svcName);
}

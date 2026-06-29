package com.idfcfirstbank.integration.edges.sfdcingress.domain.port;

/**
 * OUT port for inbound authentication (the Hydra + Kong two-token scheme,
 * mocked in Slice 1). Secrets/URLs are config/Vault concerns of the adapter,
 * never the domain.
 */
public interface AuthTokenPort {
    /** @return true if the presented credential is valid for this edge. */
    boolean authenticate(String presentedToken);
}

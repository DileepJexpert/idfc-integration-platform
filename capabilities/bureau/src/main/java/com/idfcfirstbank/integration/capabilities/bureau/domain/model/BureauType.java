package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * The credit bureaus this capability can fan out to. Each maps to a vendor port +
 * adapter; adding one is an adapter + config, not a new service.
 */
public enum BureauType {
    CIBIL,
    MULTI_BUREAU,
    COMMERCIAL,
    SCORECARD_INFRA
}

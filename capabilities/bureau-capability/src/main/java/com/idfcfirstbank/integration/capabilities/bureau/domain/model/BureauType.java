package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * Which credit bureau to pull (canonical §2.3). Each value is served by exactly
 * one OUT port / vendor adapter; adding a bureau is a new adapter + a new enum
 * value, never a new service.
 */
public enum BureauType {
    CIBIL,          // direct CIBIL (consumer)
    MULTI_BUREAU,   // multi-bureau aggregator
    COMMERCIAL,     // Commercial Bureau (CBA) — entity/business
    BUREAU_SCORE    // Bureau Scorecard (BIL) via the internal scorecard infra
}

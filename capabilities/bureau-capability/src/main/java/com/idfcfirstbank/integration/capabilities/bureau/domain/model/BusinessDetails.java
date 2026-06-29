package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/** Optional business identity (used by COMMERCIAL pulls). Nullable on the applicant. */
public record BusinessDetails(String entityName, String entityType, String udyamRef) {
}

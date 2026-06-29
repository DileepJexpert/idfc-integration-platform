package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/** Optional GST identity (used by COMMERCIAL pulls). Nullable on the applicant. */
public record GstDetails(String gstin, String legalName, String registrationState) {
}

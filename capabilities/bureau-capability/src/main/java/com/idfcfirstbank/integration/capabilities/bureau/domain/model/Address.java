package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/** Canonical applicant address. Exact bureau field mapping confirmed at harvest (step 3). */
public record Address(
        String line1,
        String line2,
        String city,
        String state,
        String pincode,
        String addressType) {
}

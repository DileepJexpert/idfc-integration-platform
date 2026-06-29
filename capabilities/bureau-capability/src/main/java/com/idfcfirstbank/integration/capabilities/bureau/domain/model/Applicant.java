package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Canonical applicant identity — the SUPERSET of the identifiers the absorbed
 * services sent (§2.3). {@code aadharRef} is a TOKEN/reference, never raw Aadhaar
 * (PII stays out of the domain). {@code gstDetails}/{@code businessDetails} are
 * present only for COMMERCIAL pulls.
 */
public record Applicant(
        String firstName,
        String middleName,
        String lastName,
        LocalDate dob,
        String pan,
        String aadharRef,
        List<Address> addresses,
        String phone,
        String email,
        GstDetails gstDetails,
        BusinessDetails businessDetails) {

    public Applicant {
        addresses = addresses == null ? List.of() : List.copyOf(addresses);
    }
}

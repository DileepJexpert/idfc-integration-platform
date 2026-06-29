package com.idfcfirstbank.integration.capabilities.kyc.domain.model;

/**
 * KYC verification outcome from the vendor (NSDL). KYC is an INTEGRATION — it
 * verifies the applicant against the vendor, it does not own the record.
 */
public record KycResult(String status, String kycRefId) {
}

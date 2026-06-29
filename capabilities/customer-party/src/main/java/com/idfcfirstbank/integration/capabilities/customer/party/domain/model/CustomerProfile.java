package com.idfcfirstbank.integration.capabilities.customer.party.domain.model;

/**
 * Resolved customer profile from the source of truth (Posidex/CDP). Customer/
 * Party is an INTEGRATION — it resolves against the master, it does not own it.
 */
public record CustomerProfile(String crn, String customerId, String name, String status) {
}

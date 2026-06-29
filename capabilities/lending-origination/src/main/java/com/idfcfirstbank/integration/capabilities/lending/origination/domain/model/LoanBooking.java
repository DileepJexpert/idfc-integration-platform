package com.idfcfirstbank.integration.capabilities.lending.origination.domain.model;

/**
 * Result of booking a loan in FinnOne. {@code loanId} is the FinnOne loan account
 * number (LAN); {@code status} is the booking outcome (e.g. {@code "BOOKED"}).
 * lending-origination is an INTEGRATION — FinnOne owns the loan, this capability
 * just triggers the booking and reports back the LAN.
 */
public record LoanBooking(String loanId, String status) {
}

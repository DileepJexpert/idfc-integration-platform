package com.idfcfirstbank.integration.capabilities.lending.origination.domain.port;

import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;

import java.util.Map;

/**
 * OUT port to FinnOne (loan booking system of record). The real adapter is an
 * Oracle STORED PROCEDURE over JDBC (SP_FINNONE_SUBMISSION) — NOT HTTP; the mock
 * adapter books locally. The domain never knows which is wired.
 */
public interface FinnOneBookingPort {
    /** Book the loan for an APPROVED application; returns the FinnOne LAN + status. */
    LoanBooking book(Map<String, Object> application);
}

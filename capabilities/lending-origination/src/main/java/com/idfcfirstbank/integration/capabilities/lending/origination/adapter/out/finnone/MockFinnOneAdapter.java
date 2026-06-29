package com.idfcfirstbank.integration.capabilities.lending.origination.adapter.out.finnone;

import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;

import java.util.Map;

/**
 * Local mock FinnOne — deterministic loan booking derived from the application's
 * {@code applicationRef}. Used for unit tests and when
 * {@code idfc.lending-origination.finnone.mode=mock} (no Oracle/stored proc needed).
 */
public class MockFinnOneAdapter implements FinnOneBookingPort {

    @Override
    public LoanBooking book(Map<String, Object> application) {
        return new LoanBooking("LN-" + application.getOrDefault("applicationRef", "REF"), "BOOKED");
    }
}

package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

/** FinnOne foreclosure — READ ONLY (BRD §4: servicing never books). Mocked. */
public interface FinnOneForeclosurePort {
    /** @return the foreclosure amount for the LAN. */
    double foreclosureAmount(String lan);
}

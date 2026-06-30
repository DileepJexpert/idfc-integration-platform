package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

/** Create an SFDC PDA / closure case for manual follow-up. Mocked. */
public interface SfdcCasePort {
    String createClosureCase(String lan);
}

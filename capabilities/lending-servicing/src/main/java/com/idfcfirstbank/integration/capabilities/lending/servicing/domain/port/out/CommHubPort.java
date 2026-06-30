package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

/** CommHub notification (used when no partner payment is found). Mocked. */
public interface CommHubPort {
    void notify(String lan, String message);
}

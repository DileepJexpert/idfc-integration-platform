package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out;

/** Query SFDC for an existing partner payment. Mocked. */
public interface SfdcPartnerPaymentPort {
    boolean hasPartnerPayment(String lan);
}

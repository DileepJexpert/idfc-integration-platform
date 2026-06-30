package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

import java.util.Map;

/** Build + deliver the autopay link: QuickPay (UPI intent) -> Dwarf (shorten) ->
 * SFDC-SMS (send). Mocked locally as one chained step. */
public interface AutopayLinkPort {
    /** @return the shortened autopay link that was sent. */
    String setupAndSend(String invoiceNo, Map<String, Object> mandate);
}

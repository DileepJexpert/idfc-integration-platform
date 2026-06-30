package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock;

import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.AutopayLinkPort;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Mock QuickPay -> Dwarf -> SFDC-SMS chain — returns the shortened link "sent". */
@Component
public class MockAutopayLinkAdapter implements AutopayLinkPort {
    @Override
    public String setupAndSend(String invoiceNo, Map<String, Object> mandate) {
        return "https://idfcfirst.in/p/" + Integer.toHexString(invoiceNo.hashCode());
    }
}

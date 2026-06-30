package com.idfcfirstbank.integration.capabilities.mandate.adapter.out.mock;

import com.idfcfirstbank.integration.capabilities.mandate.domain.port.out.CbsNachPort;
import org.springframework.stereotype.Component;

/** Mock CBS NACH — EnquireNACHMandate "finds" the mandate unless invoiceNo
 * contains "MISSING"; CreateNACHMandate(cancel) is a no-op. */
@Component
public class MockCbsNachAdapter implements CbsNachPort {
    @Override
    public boolean enquire(String invoiceNo) {
        return invoiceNo == null || !invoiceNo.toUpperCase().contains("MISSING");
    }

    @Override
    public void cancel(String invoiceNo) {
        // mock: nothing to do
    }
}

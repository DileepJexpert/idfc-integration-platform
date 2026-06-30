package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

/** CBS NACH operations for cancellation (BRD §3): EnquireNACHMandate then, if
 * found, CreateNACHMandate(cancel). Mocked locally. */
public interface CbsNachPort {
    boolean enquire(String invoiceNo);
    void cancel(String invoiceNo);
}

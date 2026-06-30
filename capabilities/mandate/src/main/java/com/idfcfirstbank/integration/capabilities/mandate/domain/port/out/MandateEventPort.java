package com.idfcfirstbank.integration.capabilities.mandate.domain.port.out;

import java.util.Map;

/** Emit the {@code MandateCallback} domain event (correlation = invoiceNo) that a
 * journey {@code wait} node correlates on (BRD §2/§3, engine wait is T3). */
public interface MandateEventPort {
    void emitMandateCallback(String invoiceNo, Map<String, Object> event);
}

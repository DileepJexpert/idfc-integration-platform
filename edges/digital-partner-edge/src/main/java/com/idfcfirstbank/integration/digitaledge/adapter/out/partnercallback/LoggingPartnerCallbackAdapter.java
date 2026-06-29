package com.idfcfirstbank.integration.digitaledge.adapter.out.partnercallback;

import com.idfcfirstbank.integration.digitaledge.domain.port.PartnerCallbackPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock partner callback (the demo's partner-side push-back). A real adapter would
 * POST the decision to the partner's configured callback URL; here it logs, which
 * is the observable proof that the decision returned to the right partner.
 */
@Component
public class LoggingPartnerCallbackAdapter implements PartnerCallbackPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingPartnerCallbackAdapter.class);

    @Override
    public void pushDecision(String partner, String applicationRef, String outcome, String loanId,
                             String correlationId) {
        log.info("digital.callback partner={} applicationRef={} outcome={} loanId={} correlationId={}",
                partner, applicationRef, outcome, loanId, correlationId);
    }
}

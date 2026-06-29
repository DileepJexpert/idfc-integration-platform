package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.SfdcResponsePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Mock SFDC push-back (real Kong delivery is a later slice). Invoked EXCLUSIVELY
 * on the successful CAS transition INTO DECIDED (C1) — never on a resend read —
 * so a single logged push per decision is the observable proof of the guard.
 */
@Component
public class MockSfdcResponseAdapter implements SfdcResponsePort {

    private static final Logger log = LoggerFactory.getLogger(MockSfdcResponseAdapter.class);

    @Override
    public void pushDecision(IdempotencyRecord decidedRecord, String correlationId) {
        var decision = decidedRecord.decision();
        log.info("sfdc.push-decision notificationId={} outcome={} applicationId={} correlationId={}",
                decidedRecord.notificationId(),
                decision == null ? "<none>" : decision.outcome(),
                decision == null ? "<none>" : decision.applicationId(),
                correlationId);
    }
}

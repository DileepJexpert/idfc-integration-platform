package com.idfcfirstbank.integration.capabilities.verification.domain.port.out;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;

/**
 * Notify SFDC of a TERMINAL technical failure (spec v2 §C.3): on DLQ, the assisted
 * journey must tell SFDC the run FAILED (via the consolidated SFDC Response egress
 * capability, §B). This is NOT a business decline — those flow as normal decisions.
 * PII: implementations log ids + reason only, never the payload body.
 */
public interface SfdcNotifyPort {
    void notifyFailure(CapabilityRequest request, String reason);
}

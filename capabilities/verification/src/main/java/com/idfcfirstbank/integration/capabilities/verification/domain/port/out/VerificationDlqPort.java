package com.idfcfirstbank.integration.capabilities.verification.domain.port.out;

import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;

/**
 * Dead-letter a verification that failed after retries (correction #2). The old
 * wrapper silently ack'd and LOST failed messages; here they are durably parked,
 * visible and replayable. PII: implementations must not log the payload body.
 */
public interface VerificationDlqPort {
    void deadLetter(CapabilityRequest request, String reason);
}

package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.SfdcNotifyPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.capability.RetryExecutor;
import com.idfcfirstbank.integration.shared.capability.RetryPolicy;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The verification dispatch (spec v2 §C) — CLASSIFIED retry, not blind. Runs the operation
 * through the shared {@link RetryExecutor} with a {@link RetryPolicy} (verifications are
 * idempotent reads: retry TRANSIENT + idempotent-AMBIGUOUS, exp backoff + jitter). On a
 * TERMINAL technical failure it routes to the DLQ AND notifies SFDC (§C.3) — the run fails,
 * never a silent ack. BUSINESS declines never reach here: they are HTTP-200 envelopes the
 * journey branch declines on.
 *
 * <p>PII: only svcName + classification + error code are logged, never the payload.
 */
public class VerificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(VerificationDispatcher.class);

    private final VerificationService service;
    private final VerificationDlqPort dlq;
    private final SfdcNotifyPort sfdcNotify;
    private final RetryExecutor retry;
    private final RetryPolicy policy;

    public VerificationDispatcher(VerificationService service, VerificationDlqPort dlq,
                                  SfdcNotifyPort sfdcNotify, RetryExecutor retry, RetryPolicy policy) {
        this.service = service;
        this.dlq = dlq;
        this.sfdcNotify = sfdcNotify;
        this.retry = retry;
        this.policy = policy;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        String svcName = request.operation();
        try {
            Map<String, Object> envelope = retry.execute(policy, () -> service.verify(svcName, request.payload()));
            return CapabilityResponse.ok(request, envelope);
        } catch (VerificationException e) {
            return terminalFailure(request, svcName, e.errorClass(), e.errorClass() + ":" + e.errorCode());
        } catch (RuntimeException e) {
            // Unknown failure: PERMANENT (never blind-retry a bug), DLQ + notify.
            return terminalFailure(request, svcName, ErrorClass.PERMANENT, "UNEXPECTED:" + e.getClass().getName());
        }
    }

    /** Terminal technical failure: DLQ (audit, replay by NEW correlationId) + notify SFDC (run FAILED). */
    private CapabilityResponse terminalFailure(CapabilityRequest request, String svcName,
                                               ErrorClass errorClass, String reason) {
        dlq.deadLetter(request, reason);
        sfdcNotify.notifyFailure(request, reason);
        log.error("verify.terminal-failure svcName={} class={} reason={} -> DLQ + notifySfdc (run FAILED) ALERT",
                svcName, errorClass, reason);
        return CapabilityResponse.error(request, errorClass);
    }
}

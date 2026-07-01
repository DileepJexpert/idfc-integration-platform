package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * The retry + DLQ core (correction #2 — the whole point vs the old wrapper). Runs the
 * verification; retries TRANSIENT failures up to {@code maxAttempts} (fixed backoff);
 * on a PERMANENT failure or exhausted retries it routes the message to the DLQ and
 * returns an error response — it NEVER silently acks/loses the message.
 *
 * <p>PII: only the svcName + classification + error code are logged, never the payload
 * (reg numbers, emails, account numbers).
 */
public class VerificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(VerificationDispatcher.class);

    private final VerificationService service;
    private final VerificationDlqPort dlq;
    private final int maxAttempts;
    private final long backoffMillis;

    public VerificationDispatcher(VerificationService service, VerificationDlqPort dlq,
                                  int maxAttempts, long backoffMillis) {
        this.service = service;
        this.dlq = dlq;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0, backoffMillis);
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        String svcName = request.operation();
        VerificationException last = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> envelope = service.verify(svcName, request.payload());
                return CapabilityResponse.ok(request, envelope);
            } catch (VerificationException e) {
                last = e;
                if (e.errorClass() != ErrorClass.TRANSIENT) {
                    break;                       // permanent: no point retrying
                }
                log.warn("verify.transient svcName={} attempt={}/{} code={}",
                        svcName, attempt, maxAttempts, e.errorCode());
                if (attempt < maxAttempts) {
                    sleep();
                }
            } catch (RuntimeException e) {
                // Unknown failure: do NOT retry by default (avoid storms on bugs); DLQ it.
                last = new VerificationException(ErrorClass.PERMANENT, "UNEXPECTED", e.getClass().getName());
                break;
            }
        }

        // Retries exhausted / permanent -> DLQ (never silent-ack), and return an error.
        String reason = (last == null ? "UNKNOWN" : last.errorClass() + ":" + last.errorCode());
        dlq.deadLetter(request, reason);
        log.error("verify.dlq svcName={} reason={} — parked in DLQ (not lost) ALERT", svcName, reason);
        return CapabilityResponse.error(request, last == null ? ErrorClass.PERMANENT : last.errorClass());
    }

    private void sleep() {
        if (backoffMillis == 0) return;
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.idfcfirstbank.integration.capabilities.verification.config;

import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationService;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.SfdcNotifyPort;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import com.idfcfirstbank.integration.shared.capability.Backoff;
import com.idfcfirstbank.integration.shared.capability.RetryExecutor;
import com.idfcfirstbank.integration.shared.capability.RetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the classified retry-policy engine (spec v2 §C) into the verification dispatcher:
 * verifications are IDEMPOTENT READS, so the policy retries TRANSIENT (and idempotent-
 * AMBIGUOUS) with exponential backoff + jitter, then DLQ + notifySfdc.
 */
@Configuration
public class VerificationConfiguration {

    @Bean
    VerificationDispatcher verificationDispatcher(VerificationService service, VerificationDlqPort dlq,
                                                  SfdcNotifyPort sfdcNotify, VerificationProperties props) {
        RetryPolicy policy = RetryPolicy.idempotentReads(
                props.retry().maxAttempts(),
                Backoff.exponential(props.retry().backoffMillis(), props.retry().maxBackoffMillis(),
                        props.retry().jitter()));
        return new VerificationDispatcher(service, dlq, sfdcNotify, RetryExecutor.withRealSleep(), policy);
    }
}

package com.idfcfirstbank.integration.capabilities.verification.config;

import com.idfcfirstbank.integration.capabilities.verification.application.VerificationDispatcher;
import com.idfcfirstbank.integration.capabilities.verification.application.VerificationService;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationDlqPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the retry/DLQ dispatcher from the configured policy. */
@Configuration
public class VerificationConfiguration {

    @Bean
    VerificationDispatcher verificationDispatcher(VerificationService service, VerificationDlqPort dlq,
                                                  VerificationProperties properties) {
        return new VerificationDispatcher(service, dlq,
                properties.retry().maxAttempts(), properties.retry().backoffMillis());
    }
}

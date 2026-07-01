package com.idfcfirstbank.integration.capabilities.verification;

import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** The verification capability service (svcName -> adapter/mapper -> downstream). */
@SpringBootApplication
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(VerificationApplication.class, args);
    }
}

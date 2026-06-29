package com.idfcfirstbank.integration.capabilities.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * kyc capability app. Consumes {@code cap.kyc.request.v1}, verifies the
 * applicant's KYC against NSDL (mock/real adapter), and replies on
 * {@code cap.kyc.response.v1} per THE CAPABILITY CONTRACT.
 */
@SpringBootApplication
public class KycApplication {
    public static void main(String[] args) {
        SpringApplication.run(KycApplication.class, args);
    }
}

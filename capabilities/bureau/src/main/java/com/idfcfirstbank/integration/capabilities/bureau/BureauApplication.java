package com.idfcfirstbank.integration.capabilities.bureau;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * bureau capability app. Consumes {@code cap.bureau.request.v1}, fetches the
 * applicant's credit bureau report + score from CIBIL (mock/real adapter), and
 * replies on {@code cap.bureau.response.v1} per THE CAPABILITY CONTRACT. Bureau
 * ONLY fetches data — it does not make a credit decision (that is scoring).
 */
@SpringBootApplication
public class BureauApplication {
    public static void main(String[] args) {
        SpringApplication.run(BureauApplication.class, args);
    }
}

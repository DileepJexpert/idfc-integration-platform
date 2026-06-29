package com.idfcfirstbank.integration.capabilities.customer.party;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-party capability app. Consumes {@code cap.customer-party.request.v1},
 * resolves the customer against Posidex (mock/real adapter), and replies on
 * {@code cap.customer-party.response.v1} per THE CAPABILITY CONTRACT.
 */
@SpringBootApplication
public class CustomerPartyApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerPartyApplication.class, args);
    }
}

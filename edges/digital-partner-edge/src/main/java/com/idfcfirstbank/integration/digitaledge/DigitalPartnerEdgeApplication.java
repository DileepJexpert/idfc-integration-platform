package com.idfcfirstbank.integration.digitaledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The digital-partner edge. Receives fintech-partner loan-origination over sync
 * REST and publishes the SAME shared canonical envelope to the SAME origination
 * topic the SFDC edge uses — so the engine and capabilities serve digital
 * UNCHANGED. One platform, many doors.
 */
@SpringBootApplication
public class DigitalPartnerEdgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(DigitalPartnerEdgeApplication.class, args);
    }
}

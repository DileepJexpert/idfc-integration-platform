package com.idfcfirstbank.integration.orchestration.originationjourney;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The orchestration ENGINE. Loads the locked journey contract, consumes inbound
 * origination envelopes + every {@code cap.*.response.v1}, walks the DAG, and
 * pushes the final decision back. Kafka and instance state sit behind OUT ports;
 * the DAG-walk logic is framework-free (see {@code domain.service.JourneyEngine}).
 */
@SpringBootApplication
public class
OriginationJourneyApplication {
    public static void main(String[] args) {
        SpringApplication.run(OriginationJourneyApplication.class, args);
    }
}

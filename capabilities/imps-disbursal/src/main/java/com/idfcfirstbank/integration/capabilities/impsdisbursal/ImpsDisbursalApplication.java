package com.idfcfirstbank.integration.capabilities.impsdisbursal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The imps-disbursal SYNC deployable: a REST door ({@code POST /api/v1/impsFT})
 * that runs the fund transfer in-thread and returns the result on the same call.
 * It is deliberately NOT wired to Kafka or the journey engine — the digital-lending
 * sync lane is a separate execution path (the caller blocks for the answer).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ImpsDisbursalApplication {
    public static void main(String[] args) {
        SpringApplication.run(ImpsDisbursalApplication.class, args);
    }
}

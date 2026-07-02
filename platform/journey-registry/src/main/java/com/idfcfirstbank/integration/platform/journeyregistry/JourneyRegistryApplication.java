package com.idfcfirstbank.integration.platform.journeyregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The journey registry — the control-plane service the DAG Designer writes to
 * and the engine reads published journey configs from. See module build file.
 */
@SpringBootApplication
public class JourneyRegistryApplication {

    public static void main(String[] args) {
        SpringApplication.run(JourneyRegistryApplication.class, args);
    }
}

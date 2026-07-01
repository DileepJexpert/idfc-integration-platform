package com.idfcfirstbank.integration.capabilities.communications;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The communications capability service (SENDSMS/OTP action handler). */
@SpringBootApplication
public class CommunicationsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommunicationsApplication.class, args);
    }
}

package com.idfcfirstbank.integration.sfdcresponse;

import com.idfcfirstbank.integration.sfdcresponse.config.SfdcResponseProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** The consolidated per-org SFDC egress capability (spec v2 §B). */
@SpringBootApplication
@EnableConfigurationProperties(SfdcResponseProperties.class)
public class SfdcResponseApplication {
    public static void main(String[] args) {
        SpringApplication.run(SfdcResponseApplication.class, args);
    }
}

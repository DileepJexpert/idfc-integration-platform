package com.idfcfirstbank.integration.capabilities.lending.origination;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * lending-origination capability app. Consumes {@code cap.lending-origination.request.v1},
 * and on an APPROVED application BOOKS the loan in FinnOne (mock/real adapter),
 * replying on {@code cap.lending-origination.response.v1} per THE CAPABILITY CONTRACT.
 *
 * <p>FinnOne's real integration is an Oracle STORED PROCEDURE over JDBC (NOT HTTP).
 * spring-boot-starter-jdbc is on the classpath, so {@link DataSourceAutoConfiguration}
 * is EXCLUDED here to let the app start in mock mode without any
 * {@code spring.datasource.*}. In real mode ({@code finnone.mode=real}) the config
 * builds and uses the DataSource from {@code spring.datasource.*} itself.
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class LendingOriginationApplication {
    public static void main(String[] args) {
        SpringApplication.run(LendingOriginationApplication.class, args);
    }
}

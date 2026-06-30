package com.idfcfirstbank.integration.brandrouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.idfcfirstbank.integration.brandrouter.config.BrandRouterProperties;

@SpringBootApplication
@EnableConfigurationProperties(BrandRouterProperties.class)
public class BrandRouterApplication {
    public static void main(String[] args) {
        SpringApplication.run(BrandRouterApplication.class, args);
    }
}

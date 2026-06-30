package com.idfcfirstbank.integration.capabilities.lending.servicing.adapter.out.mock;

import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.CommHubPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.FinnOneForeclosurePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.MssfPort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcCasePort;
import com.idfcfirstbank.integration.capabilities.lending.servicing.domain.port.out.SfdcPartnerPaymentPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * The mocked servicing externals (the only impls; real FinnOne-READ / SFDC /
 * CommHub / MSSF-via-Kong are config-driven later steps, §10). FinnOne foreclosure
 * returns 0 (closeable) unless the LAN contains "DUE".
 */
@Configuration
public class MockServicingAdapters {

    @Bean
    FinnOneForeclosurePort finnOneForeclosurePort() {
        return lan -> lan != null && lan.toUpperCase().contains("DUE") ? 1500.0 : 0.0;
    }

    @Bean
    SfdcCasePort sfdcCasePort() {
        return lan -> "CASE-" + lan;
    }

    @Bean
    SfdcPartnerPaymentPort sfdcPartnerPaymentPort() {
        return lan -> lan != null && lan.toUpperCase().contains("PAID");
    }

    @Bean
    CommHubPort commHubPort() {
        return (lan, message) -> { /* mock: no-op notify */ };
    }

    @Bean
    MssfPort mssfPort() {
        // mock Kong token -> AES encrypt -> POST -> decrypt; returns a canned result.
        return (kind, loanRef) -> Map.of("kind", kind, "loanRef", loanRef, "value", "OK");
    }
}

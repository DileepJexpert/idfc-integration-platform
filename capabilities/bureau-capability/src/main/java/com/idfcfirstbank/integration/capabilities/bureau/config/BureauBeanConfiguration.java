package com.idfcfirstbank.integration.capabilities.bureau.config;

import com.idfcfirstbank.integration.capabilities.bureau.domain.port.in.FetchBureauData;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CibilBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.CommercialBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.MultiBureauPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.out.ScorecardInfraPort;
import com.idfcfirstbank.integration.capabilities.bureau.domain.service.BureauService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the pure {@link BureauService} to its OUT ports (the profile-switched
 * vendor adapters) and a bounded fan-out executor. The concrete adapters are
 * never referenced here by type — only via their ports — so swapping mock→real
 * is a profile change, not a wiring change.
 */
@Configuration
public class BureauBeanConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Bounded pool for the parallel per-bureau fan-out (one slot per concurrent vendor call). */
    @Bean(destroyMethod = "shutdown")
    Executor bureauFanoutExecutor() {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bureau-fanout-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(8, factory);
    }

    @Bean
    FetchBureauData fetchBureauData(CibilBureauPort cibil, MultiBureauPort multiBureau,
                                    CommercialBureauPort commercial, ScorecardInfraPort scorecardInfra,
                                    Executor bureauFanoutExecutor) {
        return new BureauService(cibil, multiBureau, commercial, scorecardInfra, bureauFanoutExecutor);
    }
}

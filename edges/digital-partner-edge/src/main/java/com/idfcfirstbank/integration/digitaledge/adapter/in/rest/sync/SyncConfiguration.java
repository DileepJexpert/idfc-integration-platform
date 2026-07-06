package com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.ImpsDisbursalModule;
import com.idfcfirstbank.integration.capabilities.lmsutilities.LmsUtilitiesModule;
import com.idfcfirstbank.integration.digitaledge.config.SyncEdgeProperties;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncInvocable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.List;

/**
 * Assembles the digital-lending SYNC lane inside the edge: {@code @Import}s each
 * sync capability MODULE (imps-disbursal, lms-utilities) so their beans register
 * in this context, then builds the {@link SyncCapabilityInvoker} over every
 * registered {@link SyncInvocable}. The capabilities are invoked IN-THREAD — the
 * async engine/Kafka path is untouched.
 */
@Configuration
@Import({ImpsDisbursalModule.class, LmsUtilitiesModule.class})
@EnableConfigurationProperties(SyncEdgeProperties.class)
public class SyncConfiguration {

    @Bean
    public SyncCapabilityInvoker syncCapabilityInvoker(List<SyncInvocable> invocables) {
        return new SyncCapabilityInvoker(invocables);
    }
}

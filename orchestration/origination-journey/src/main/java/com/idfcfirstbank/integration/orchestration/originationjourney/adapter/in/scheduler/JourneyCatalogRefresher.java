package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.in.scheduler;

import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyRegistry;
import com.idfcfirstbank.integration.orchestration.originationjourney.config.EngineProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-reads the published-journey snapshot from the registry, so a
 * checker's publish reaches running engines WITHOUT a redeploy (new runs start
 * on the new version; pinned in-flight runs are untouched). Registry mode only —
 * a classpath JAR cannot change under a running process, so refreshing it would
 * only re-parse the same bytes. Refresh failures keep the last-known snapshot
 * (see {@link JourneyRegistry#refresh()}).
 */
@Component
public class JourneyCatalogRefresher {

    private final JourneyRegistry registry;
    private final boolean registrySource;

    public JourneyCatalogRefresher(JourneyRegistry registry, EngineProperties props) {
        this.registry = registry;
        this.registrySource = props.usesRegistrySource();
    }

    @Scheduled(
            fixedDelayString = "#{${idfc.engine.registry.refresh-seconds:30} * 1000}",
            initialDelayString = "#{${idfc.engine.registry.refresh-seconds:30} * 1000}")
    public void refresh() {
        if (registrySource) {
            registry.refresh();
        }
    }
}

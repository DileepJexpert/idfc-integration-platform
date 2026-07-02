package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneySource;

import java.util.List;
import java.util.Optional;

/**
 * The EXPLICIT bootstrap fallback {@link JourneySource}: journeys shipped inside
 * the JAR ({@code idfc.engine.journey-source=classpath}, the Docker-free default
 * for dev/tests). NOT version-safe across deploys — a JAR can only ever serve the
 * one version it ships, so a run pinned to an older version fails closed here
 * with a message that says to use the registry source. The startup log flags
 * this source loudly for the same reason.
 */
public class ClasspathJourneySource implements JourneySource {

    private final JourneyDefinitionLoader loader;
    private final List<String> resources;

    public ClasspathJourneySource(JourneyDefinitionLoader loader, List<String> resources) {
        this.loader = loader;
        this.resources = List.copyOf(resources);
    }

    @Override
    public List<JourneyDefinition> loadCurrent() {
        return resources.stream().map(loader::loadFromClasspath).toList();
    }

    @Override
    public Optional<JourneyDefinition> load(String journeyKey, int version) {
        // A JAR has no history: only the exact shipped version can be served.
        return loadCurrent().stream()
                .filter(d -> d.key().equals(journeyKey) && d.version() == version)
                .findFirst();
    }

    @Override
    public String describe() {
        return "classpath" + resources;
    }
}

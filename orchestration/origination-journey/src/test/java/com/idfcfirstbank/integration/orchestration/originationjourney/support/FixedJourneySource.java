package com.idfcfirstbank.integration.orchestration.originationjourney.support;

import com.idfcfirstbank.integration.orchestration.originationjourney.application.JourneyRegistry;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneySource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Test {@link JourneySource} over a fixed in-memory definition list — the seam
 * the A2 tests flip to simulate publishes ({@link #replaceCurrent}) and outages
 * ({@link #failNextLoads}). {@link #load} serves ANY version ever seen (like the
 * registry, which never forgets a published version).
 */
public class FixedJourneySource implements JourneySource {

    private volatile List<JourneyDefinition> current;
    private final Map<String, JourneyDefinition> everPublished = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile RuntimeException failure;

    public FixedJourneySource(List<JourneyDefinition> current) {
        this.current = List.copyOf(current);
        current.forEach(d -> everPublished.put(d.key() + ":" + d.version(), d));
    }

    /** Simulate a checker publish: new current snapshot, history retained. */
    public void replaceCurrent(List<JourneyDefinition> defs) {
        this.current = List.copyOf(defs);
        defs.forEach(d -> everPublished.put(d.key() + ":" + d.version(), d));
    }

    /** Simulate a registry outage: every load throws until cleared with null. */
    public void failNextLoads(RuntimeException e) {
        this.failure = e;
    }

    @Override
    public List<JourneyDefinition> loadCurrent() {
        if (failure != null) {
            throw failure;
        }
        return current;
    }

    @Override
    public Optional<JourneyDefinition> load(String journeyKey, int version) {
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(everPublished.get(journeyKey + ":" + version));
    }

    @Override
    public String describe() {
        return "fixed-test-source";
    }

    /** One-liner for tests: build + bootstrap a registry over fixed definitions. */
    public static JourneyRegistry registry(Map<String, String> typeToJourney, JourneyDefinition... defs) {
        JourneyRegistry registry = new JourneyRegistry(new FixedJourneySource(List.of(defs)), typeToJourney);
        registry.bootstrap();
        return registry;
    }
}

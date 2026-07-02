package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.ClasspathJourneySource;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.support.FixedJourneySource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A2's registry-down behavior is CHOSEN, not inherited from whatever the HTTP
 * client happens to do (this is where config-not-code platforms usually acquire
 * their first accidental availability coupling). The contract, per phase:
 *
 * <ul>
 *   <li>BOOTSTRAP down/empty -> refuse to start (an engine with no journeys
 *       would fail every consumed origination event);</li>
 *   <li>REFRESH down/empty -> keep serving the last-known snapshot, warn (a
 *       registry blip must not take down a running engine);</li>
 *   <li>PINNED FETCH down -> throw (the triggering record redelivers and
 *       retries); only a PROVEN never-published answer fails the run;</li>
 *   <li>classpath source -> pinned historical versions are honestly
 *       unavailable, with a message that says to use the registry.</li>
 * </ul>
 */
class RegistryDownBehaviorTest {

    private static JourneyDefinition def(String key, int version) {
        JourneyNode work = JourneyNode.task("n_work", null, "kyc", "verify", null,
                "context.work", null, null, null, false, List.of("n_done"));
        JourneyNode done = JourneyNode.terminal("n_done", "push_decision_to_channel",
                List.of(), "completed");
        return new JourneyDefinition(key, version, "n_work", List.of(work, done));
    }

    // ---- bootstrap: fail closed -----------------------------------------------

    @Test
    void registryDownAtStartupRefusesToStart() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 1)));
        source.failNextLoads(new RuntimeException("connection refused"));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of());

        assertThatThrownBy(registry::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start")
                .hasRootCauseMessage("connection refused");
    }

    @Test
    void anEmptyCatalogAtStartupAlsoRefusesToStart() {
        JourneyRegistry registry = new JourneyRegistry(new FixedJourneySource(List.of()), Map.of());

        assertThatThrownBy(registry::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EMPTY catalog");
    }

    // ---- refresh: keep serving the last-known snapshot ---------------------------

    @Test
    void aRefreshFailureKeepsServingTheLastKnownSnapshot() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 1)));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of("PERSONAL_LOAN", "j"));
        registry.bootstrap();

        source.failNextLoads(new RuntimeException("registry blip"));

        assertThatCode(registry::refresh)
                .as("a refresh failure must not take down a running engine")
                .doesNotThrowAnyException();
        assertThat(registry.resolveForType("PERSONAL_LOAN").version())
                .as("still serving the last-known snapshot")
                .isEqualTo(1);
    }

    @Test
    void aRefreshThatReturnsAnEmptyCatalogIsIgnoredNotApplied() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 1)));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of("PERSONAL_LOAN", "j"));
        registry.bootstrap();

        source.replaceCurrent(List.of());
        registry.refresh();

        assertThat(registry.resolveForType("PERSONAL_LOAN").version()).isEqualTo(1);
    }

    // ---- pinned fetch: redeliver-and-retry vs proven-permanent --------------------

    @Test
    void aPinnedFetchDuringAnOutageThrowsSoTheRecordRedelivers() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 1)));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of());
        registry.bootstrap();
        source.replaceCurrent(List.of(def("j", 2)));
        registry.refresh(); // current is now v2; v1 must lazy-fetch on demand

        source.failNextLoads(new RuntimeException("registry down"));
        assertThatThrownBy(() -> registry.definitionFor("j", 1))
                .as("transient outage -> propagate -> Kafka redelivery retries the hop")
                .hasMessageContaining("registry down");

        // The outage ends — the SAME pinned resolve now succeeds (nothing was cached).
        source.failNextLoads(null);
        assertThat(registry.definitionFor("j", 1).version()).isEqualTo(1);
    }

    @Test
    void aProvenNeverPublishedPinFailsLoudly() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 1)));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of());
        registry.bootstrap();

        assertThatThrownBy(() -> registry.definitionFor("j", 42))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("j@v42")
                .hasMessageContaining("never published");
    }

    @Test
    void aLegacyUnpinnedInstanceResolvesToCurrentAsTheMigrationPath() {
        FixedJourneySource source = new FixedJourneySource(List.of(def("j", 3)));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of());
        registry.bootstrap();

        assertThat(registry.definitionFor("j", 0).version())
                .as("version 0 = persisted before pinning existed -> current, with a warn")
                .isEqualTo(3);
    }

    // ---- classpath source: honest about its limits ---------------------------------

    @Test
    void classpathSourceServesItsShippedVersionButNotHistory() {
        ClasspathJourneySource source = new ClasspathJourneySource(
                new JourneyDefinitionLoader(new ObjectMapper()),
                List.of("journeys/loan-origination.journey.json"));
        JourneyRegistry registry = new JourneyRegistry(source, Map.of());
        registry.bootstrap();

        // The shipped version (v1) resolves fine for pinned runs...
        assertThat(registry.definitionFor("loan-origination", 1).version()).isEqualTo(1);

        // ...but a pin the JAR never shipped is honestly unavailable, and the
        // message points at the fix (the registry source).
        assertThatThrownBy(() -> registry.definitionFor("loan-origination", 99))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classpath");
    }
}

package com.idfcfirstbank.integration.platform.journeyregistry.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store.InMemoryJourneyRegistryStore;
import com.idfcfirstbank.integration.platform.journeyregistry.application.JourneyConfigValidator;
import com.idfcfirstbank.integration.platform.journeyregistry.application.RegistryService;
import com.idfcfirstbank.integration.platform.journeyregistry.config.RegistrySeeder.SeedResult;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.VersionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * The local-dev seeder drives the SAME maker-checker lifecycle a human would in
 * the Designer, so a seeded journey is indistinguishable from a hand-published
 * one; a bad config is isolated (logged + counted, never fatal); and re-running
 * against a durable store is idempotent. The final case is a CI canary: every
 * bundled CANONICAL journey must survive the registry's authoritative validator
 * — if the Designer ever emits a shape the registry rejects, this goes red.
 */
class RegistrySeederTest {

    // A minimal but VALID §7 graph (start -> terminal): the smallest thing the
    // authoritative validator accepts, so the test targets the seeder, not the DSL.
    private static String journey(String key) {
        return """
                {"journeyKey":"%s","version":1,"schemaVersion":2,"startNodeId":"n1",
                 "nodes":[{"id":"n1","type":"terminal","status":"completed"}]}""".formatted(key);
    }

    private static final String INVALID = """
            {"journeyKey":"seed-broken","version":1,"schemaVersion":2,"startNodeId":"n1","nodes":[]}""";

    private RegistryService newService(InMemoryJourneyRegistryStore store) {
        return new RegistryService(store, new JourneyConfigValidator(), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-04T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void seed_publishesEachJourneyThroughTheRealMakerCheckerLifecycle() {
        var store = new InMemoryJourneyRegistryStore();
        var service = newService(store);
        var seeder = new RegistrySeeder(service, new ObjectMapper());

        SeedResult result = seeder.seed(List.of(journey("device-financing"), journey("loan-origination"), INVALID));

        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);   // the empty-DAG config, isolated
        assertThat(result.skipped()).isZero();

        // Both landed as genuinely PUBLISHED versions the engine would serve.
        assertThat(service.publishedConfigs())
                .extracting(JourneyVersionRecord::journeyKey)
                .containsExactlyInAnyOrder("device-financing", "loan-origination");
        assertThat(service.publishedConfigs())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(VersionStatus.PUBLISHED));
        // Author != approver — the maker-checker rule really ran, not a back-door write.
        assertThat(service.version("device-financing", 1).authorId()).isEqualTo("seed-maker");
        assertThat(service.version("device-financing", 1).approverId()).isEqualTo("seed-checker");
        // Readable name in the Designer list.
        assertThat(service.journey("device-financing").name()).isEqualTo("Device Financing");
    }

    @Test
    void seed_isIdempotent_soRestartAgainstADurableStoreNeverErrors() {
        var store = new InMemoryJourneyRegistryStore();
        var service = newService(store);
        var seeder = new RegistrySeeder(service, new ObjectMapper());
        var configs = List.of(journey("device-financing"), journey("loan-origination"));

        assertThat(seeder.seed(configs).published()).isEqualTo(2);

        SeedResult second = seeder.seed(configs); // same "restart"
        assertThat(second.published()).isZero();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(second.failed()).isZero();
        assertThat(service.publishedConfigs()).hasSize(2); // no duplicates, no second version
    }

    @Test
    void seed_deduplicatesWithinASingleBatch() {
        var store = new InMemoryJourneyRegistryStore();
        var service = newService(store);
        var seeder = new RegistrySeeder(service, new ObjectMapper());

        SeedResult result = seeder.seed(List.of(journey("device-financing"), journey("device-financing")));

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(service.publishedConfigs()).hasSize(1);
    }

    @Test
    void humanize_titleCasesKebabAndSnake() {
        assertThat(RegistrySeeder.humanize("device-financing")).isEqualTo("Device Financing");
        assertThat(RegistrySeeder.humanize("employee_lwd_update")).isEqualTo("Employee Lwd Update");
    }

    @Test
    void bundledCanonicalJourneys_allSurviveTheAuthoritativeValidatorAndPublish() throws Exception {
        // CI canary: after processResources copies the engine's canonical journeys
        // into seed-journeys/, every one must publish clean. Skipped only when the
        // bundle is absent (e.g. an IDE run without processResources).
        Resource[] bundled = new PathMatchingResourcePatternResolver()
                .getResources(RegistrySeeder.DEFAULT_LOCATION);
        assumeThat(bundled).describedAs("bundled seed journeys (present after processResources)").isNotEmpty();

        var service = newService(new InMemoryJourneyRegistryStore());
        SeedResult result = new RegistrySeeder(service, new ObjectMapper())
                .seedFromClasspath(RegistrySeeder.DEFAULT_LOCATION);

        assertThat(result.failed())
                .describedAs("a canonical journey the registry validator rejects — the Designer/engine contract drifted")
                .isZero();
        assertThat(result.published()).isEqualTo(bundled.length);
        assertThat(service.publishedConfigs()).hasSize(bundled.length);
    }
}

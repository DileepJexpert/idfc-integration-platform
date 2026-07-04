package com.idfcfirstbank.integration.platform.journeyregistry.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.platform.journeyregistry.application.RegistryService;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LOCAL-DEV bootstrap: publish the bundled canonical journeys into an empty
 * registry so the DAG Designer (which reads the REAL registry, not a mock) lists
 * them the moment it opens, and the {@code --registry} engine seam has something
 * to load. Drives the SAME maker-checker lifecycle a human would in the Designer
 * (createJourney -> createDraft -> submit -> approve), so what gets seeded is
 * indistinguishable from a hand-published journey — no back-door store writes.
 *
 * <p>The journeys come from the engine's canonical {@code *.journey.json}
 * contract artifacts, copied into this jar at build time ({@code seed-journeys/}
 * in {@code processResources}) — ONE source of truth, no committed duplicate.
 *
 * <p>Idempotent: a journey whose key already exists is skipped, so re-running
 * against the durable Aerospike store (which survives restarts) never errors.
 * Framework-free by construction (a plain object driving {@link RegistryService})
 * so its logic is unit-testable without a Spring context.
 */
public class RegistrySeeder {

    private static final Logger log = LoggerFactory.getLogger(RegistrySeeder.class);

    /** Default classpath location the build copies the canonical journeys into. */
    public static final String DEFAULT_LOCATION = "classpath*:seed-journeys/*.journey.json";

    // Distinct local-dev identities: the registry's author != approver rule (the
    // maker-checker 403) means one actor cannot both submit and approve.
    private static final String SEED_MAKER = "seed-maker";
    private static final String SEED_CHECKER = "seed-checker";

    private final RegistryService service;
    private final ObjectMapper objectMapper;

    public RegistrySeeder(RegistryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** Scan the bundled seed journeys and publish any not already present. */
    public SeedResult seedFromClasspath(String locationPattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            log.warn("registry.seed skipped — could not scan {}: {}", locationPattern, e.toString());
            return SeedResult.EMPTY;
        }
        List<Resource> ordered = new ArrayList<>(List.of(resources));
        ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
        List<String> configs = new ArrayList<>();
        for (Resource r : ordered) {
            try {
                configs.add(new String(r.getContentAsByteArray(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("registry.seed — cannot read {}: {}", r.getFilename(), e.toString());
            }
        }
        if (configs.isEmpty()) {
            log.info("registry.seed — no bundled seed journeys on the classpath ({}); nothing to seed",
                    locationPattern);
            return SeedResult.EMPTY;
        }
        return seed(configs);
    }

    /**
     * Publish each config that is not already registered. A single bad or
     * duplicate config is logged and counted, never fatal — one unpublishable
     * journey must not stop the rest (or the registry) from coming up.
     */
    public SeedResult seed(List<String> configJsons) {
        Set<String> present = new HashSet<>();
        service.listJourneys().forEach(m -> present.add(m.key()));
        int published = 0, skipped = 0, failed = 0;
        for (String configJson : configJsons) {
            String key = keyOf(configJson);
            if (key == null) {
                log.warn("registry.seed — a seed config has no journeyKey; skipping");
                failed++;
                continue;
            }
            if (!present.add(key)) {
                skipped++; // already in the store (or a duplicate within this batch)
                continue;
            }
            try {
                publishOne(key, configJson);
                published++;
            } catch (RuntimeException e) {
                present.remove(key); // it did not actually land
                log.warn("registry.seed — could not publish '{}': {}", key, e.toString());
                failed++;
            }
        }
        log.info("registry.seed complete — published={} skipped(existing)={} failed={}"
                + " (source: bundled canonical journeys)", published, skipped, failed);
        return new SeedResult(published, skipped, failed);
    }

    /** The full maker-checker path — the exact sequence the Designer drives over HTTP. */
    private void publishOne(String key, String configJson) {
        service.createJourney(key, humanize(key), null, null, null, SEED_MAKER);
        JourneyVersionRecord draft =
                service.createDraft(key, configJson, "seeded from the bundled canonical journey", SEED_MAKER);
        service.submit(key, draft.version(), SEED_MAKER);      // authoritative §7 validation
        service.approve(key, draft.version(), SEED_CHECKER);   // author != approver -> PUBLISHED
        log.info("registry.seed published '{}' v{}", key, draft.version());
    }

    /** The config's own identity stamp is authoritative; there is no filename fallback on the wire. */
    private String keyOf(String configJson) {
        try {
            JsonNode node = objectMapper.readTree(configJson);
            JsonNode key = node == null ? null : node.get("journeyKey");
            return key == null || key.isNull() || key.asText().isBlank() ? null : key.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /** "device-financing" -> "Device Financing" (a readable name in the Designer list). */
    static String humanize(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        boolean upNext = true;
        for (char c : key.toCharArray()) {
            if (c == '-' || c == '_') {
                sb.append(' ');
                upNext = true;
            } else if (upNext) {
                sb.append(Character.toUpperCase(c));
                upNext = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Tally of one seed pass (published new, skipped existing, failed to publish). */
    public record SeedResult(int published, int skipped, int failed) {
        static final SeedResult EMPTY = new SeedResult(0, 0, 0);
    }
}

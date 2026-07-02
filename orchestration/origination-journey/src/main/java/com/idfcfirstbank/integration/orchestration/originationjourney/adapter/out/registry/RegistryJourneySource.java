package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link JourneySource} backed by the journey-registry service — the production
 * shape: what a checker publishes in the DAG Designer is what this engine runs.
 * Reads the two A1 endpoints: {@code GET /api/v1/published-journeys} (current
 * pointer set, bootstrap + refresh) and {@code GET /api/v1/published-journeys/
 * {key}/versions/{v}} (pinned historical fetch for in-flight runs).
 *
 * <p>Explicit connect/read timeouts are REQUIRED (Karza lesson): a hung registry
 * call on the consumer thread would blow past {@code max.poll.interval.ms} and
 * trigger a rebalance storm. Transport failures THROW — bootstrap turns that
 * into refuse-to-start, a pinned fetch turns it into redeliver-and-retry; a
 * REST 404 on the pinned fetch is the one PERMANENT answer ("never published")
 * and maps to {@code Optional.empty()}.
 */
public class RegistryJourneySource implements JourneySource {

    private final RestClient restClient;
    private final JourneyDefinitionLoader loader;
    private final String baseUrl;

    public RegistryJourneySource(RestClient restClient, JourneyDefinitionLoader loader, String baseUrl) {
        this.restClient = restClient;
        this.loader = loader;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<JourneyDefinition> loadCurrent() {
        JsonNode body = restClient.get()
                .uri("/api/v1/published-journeys")
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.isArray()) {
            throw new IllegalStateException("registry returned no published-journeys array");
        }
        List<JourneyDefinition> out = new ArrayList<>();
        for (JsonNode item : body) {
            out.add(parseChecked(item));
        }
        return out;
    }

    @Override
    public Optional<JourneyDefinition> load(String journeyKey, int version) {
        try {
            JsonNode item = restClient.get()
                    .uri("/api/v1/published-journeys/{key}/versions/{version}", journeyKey, version)
                    .retrieve()
                    .body(JsonNode.class);
            if (item == null) {
                throw new IllegalStateException("registry returned an empty published-journey body");
            }
            return Optional.of(parseChecked(item));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return Optional.empty(); // proven never-published — the one permanent answer
            }
            throw e; // 5xx/auth/etc: transient or misconfiguration — retry/redeliver
        }
    }

    /**
     * Parse the §7 config and verify the registry identity envelope matches the
     * config's own stamp — a mismatch means a corrupted or mis-stamped artifact,
     * which must never run.
     */
    private JourneyDefinition parseChecked(JsonNode item) {
        String envelopeKey = item.path("journeyKey").asText(null);
        int envelopeVersion = item.path("version").asInt(-1);
        JourneyDefinition def = loader.parse(item.path("config"));
        if (!def.key().equals(envelopeKey) || def.version() != envelopeVersion) {
            throw new IllegalStateException("registry integrity violation: envelope says "
                    + envelopeKey + "@v" + envelopeVersion + " but config parses as "
                    + def.key() + "@v" + def.version());
        }
        return def;
    }

    @Override
    public String describe() {
        return "registry[" + baseUrl + "]";
    }
}

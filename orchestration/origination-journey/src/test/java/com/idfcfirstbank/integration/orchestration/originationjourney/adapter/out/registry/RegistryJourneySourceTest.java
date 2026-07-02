package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader.JourneyDefinitionLoader;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The registry HTTP adapter against a mock server: token header on every call,
 * §7 parse from the A1 wire shape, the envelope-vs-config integrity check, and
 * the permanent-vs-transient split on the pinned fetch (404 = proven never
 * published; anything else = throw and let the redelivery retry).
 */
class RegistryJourneySourceTest {

    private static final String CONFIG = """
            {"journeyKey": "pl-express", "version": 2, "startNodeId": "n_a", "nodes": [
              {"id": "n_a", "type": "task", "capability": "kyc", "operation": "verify", "next": ["n_t"]},
              {"id": "n_t", "type": "terminal", "status": "completed"}
            ]}""";

    private MockRestServiceServer server;
    private RegistryJourneySource source;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://registry.test")
                .defaultHeader("X-Registry-Token", "test-token");
        server = MockRestServiceServer.bindTo(builder).build();
        source = new RegistryJourneySource(builder.build(),
                new JourneyDefinitionLoader(new ObjectMapper()), "http://registry.test");
    }

    @Test
    void loadCurrentParsesThePublishedSetAndSendsTheToken() {
        server.expect(requestTo("http://registry.test/api/v1/published-journeys"))
                .andExpect(header("X-Registry-Token", "test-token"))
                .andRespond(withSuccess(
                        "[{\"journeyKey\": \"pl-express\", \"version\": 2, \"config\": " + CONFIG + "}]",
                        MediaType.APPLICATION_JSON));

        List<JourneyDefinition> defs = source.loadCurrent();

        assertThat(defs).singleElement().satisfies(d -> {
            assertThat(d.key()).isEqualTo("pl-express");
            assertThat(d.version()).isEqualTo(2);
            assertThat(d.startNodeId()).isEqualTo("n_a");
        });
        server.verify();
    }

    @Test
    void anEnvelopeConfigIdentityMismatchIsAnIntegrityFailure() {
        server.expect(requestTo("http://registry.test/api/v1/published-journeys"))
                .andRespond(withSuccess(
                        // envelope claims v3; the stamped config says v2 — corrupt.
                        "[{\"journeyKey\": \"pl-express\", \"version\": 3, \"config\": " + CONFIG + "}]",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(source::loadCurrent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void aPinned404IsProvenNeverPublished() {
        server.expect(requestTo("http://registry.test/api/v1/published-journeys/pl-express/versions/9"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(source.load("pl-express", 9)).isEmpty();
    }

    @Test
    void aPinned500ThrowsSoTheCallerRetries() {
        server.expect(requestTo("http://registry.test/api/v1/published-journeys/pl-express/versions/2"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> source.load("pl-express", 2))
                .as("5xx is transient — redeliver and retry, never 'never published'")
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aPinnedFetchParsesTheExactVersion() {
        server.expect(requestTo("http://registry.test/api/v1/published-journeys/pl-express/versions/2"))
                .andExpect(header("X-Registry-Token", "test-token"))
                .andRespond(withSuccess(
                        "{\"journeyKey\": \"pl-express\", \"version\": 2, \"config\": " + CONFIG + "}",
                        MediaType.APPLICATION_JSON));

        assertThat(source.load("pl-express", 2)).hasValueSatisfying(d ->
                assertThat(d.version()).isEqualTo(2));
    }
}

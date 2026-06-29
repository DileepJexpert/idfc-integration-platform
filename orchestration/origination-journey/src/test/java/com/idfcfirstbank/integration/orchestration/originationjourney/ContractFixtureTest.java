package com.idfcfirstbank.integration.orchestration.originationjourney;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ENGINE-SIDE CONTRACT LOCK (Schema Lock). The journey config schema is a shared
 * contract between the DAG Designer's {@code ConfigSerializer} and this engine's
 * loader: they must agree byte-for-byte. The authoritative artifact is
 * {@code src/main/resources/journeys/loan-origination.journey.json} — the EXACT
 * JSON the DAG Designer emits.
 *
 * <p>This test is the engine half of the two-sided drift check. The real
 * {@code JourneyDefinition} loader does not exist yet (Slice 1 stub), so for now
 * this asserts the fixture is present and parses into the exact shape the
 * frontend emits — using the field names EXACTLY as emitted ({@code type},
 * {@code id}, {@code capabilityKey}, {@code next}, {@code arms},
 * {@code expression}, {@code startNodeId}). When the loader lands, replace the
 * structural asserts below with: parse into a {@code JourneyDefinition} and
 * assert the nodes/edges/branch match.
 *
 * <p>If this test fails after a change on either side, the contract drifted —
 * that is a co-lock decision, NOT a "bend the parser to compile" fix. Do not
 * rename fields or add required fields the frontend does not emit.
 */
class ContractFixtureTest {

    private static final String CONTRACT = "/journeys/loan-origination.journey.json";

    private JsonNode loadContract() throws Exception {
        try (InputStream in = ContractFixtureTest.class.getResourceAsStream(CONTRACT)) {
            assertThat(in)
                    .as("shared contract fixture %s must be on the classpath", CONTRACT)
                    .isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void contractParsesIntoTheExpectedTopLevelShape() throws Exception {
        JsonNode root = loadContract();
        assertThat(root.path("key").asText()).isEqualTo("loan-origination");
        assertThat(root.path("startNodeId").asText()).isEqualTo("n_customer");
        assertThat(root.path("nodes").isArray()).isTrue();
        assertThat(root.path("layout").isObject()).isTrue();
    }

    @Test
    void nodesUseTheRealBackendCapabilityKeys() throws Exception {
        JsonNode root = loadContract();
        List<String> capabilityKeys = new ArrayList<>();
        for (JsonNode node : root.path("nodes")) {
            if (node.has("capabilityKey")) {
                capabilityKeys.add(node.get("capabilityKey").asText());
            }
        }
        // These mirror the real modules under capabilities/. The DAG Designer's
        // doc example said "scoring-decisioning"; the real module is "scoring".
        assertThat(capabilityKeys)
                .contains("customer-party", "kyc", "bureau", "scoring", "lending-origination")
                .doesNotContain("scoring-decisioning");
    }

    @Test
    void branchNodeExposesArmsWithExpressionAndNext() throws Exception {
        JsonNode root = loadContract();
        JsonNode decide = findNode(root, "n_decide");
        assertThat(decide.path("type").asText()).isEqualTo("branch");
        JsonNode arms = decide.path("arms");
        assertThat(arms.isArray()).isTrue();
        assertThat(arms).hasSize(2);
        assertThat(arms.get(0).path("expression").asText()).isEqualTo("decision == 'APPROVED'");
        assertThat(arms.get(0).path("next").asText()).isEqualTo("n_book");
        assertThat(arms.get(1).path("next").asText()).isEqualTo("n_reject");
    }

    @Test
    void bookingNodeCarriesMeterAndCompensation() throws Exception {
        JsonNode root = loadContract();
        JsonNode book = findNode(root, "n_book");
        assertThat(book.path("type").asText()).isEqualTo("task");
        assertThat(book.path("capabilityKey").asText()).isEqualTo("lending-origination");
        assertThat(book.path("meter").asText()).isEqualTo("finnone_pool");
        assertThat(book.path("compensation").asText()).isEqualTo("n_reverse");
    }

    @Test
    void terminalNodesDeclareAnActionAndEmit() throws Exception {
        JsonNode root = loadContract();
        JsonNode done = findNode(root, "n_done");
        assertThat(done.path("type").asText()).isEqualTo("terminal");
        assertThat(done.path("action").asText()).isEqualTo("push_decision_to_channel");
        assertThat(done.path("emit").get(0).asText()).isEqualTo("LoanBooked");
    }

    private JsonNode findNode(JsonNode root, String id) {
        for (JsonNode node : root.path("nodes")) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        throw new AssertionError("node " + id + " missing from contract fixture");
    }
}

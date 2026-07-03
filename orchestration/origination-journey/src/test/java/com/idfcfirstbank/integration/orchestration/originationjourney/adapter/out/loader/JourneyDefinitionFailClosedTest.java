package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3 fail-closed gates, proven by feeding the loader BAD definitions:
 * a typo'd terminal status must never load (the old behavior silently defaulted
 * it to an APPROVED lending decision), and a join policy the engine tier cannot
 * execute must never load (the old behavior silently ran it as allOf).
 */
class JourneyDefinitionFailClosedTest {

    private final JourneyDefinitionLoader loader = new JourneyDefinitionLoader(new ObjectMapper());

    private JourneyDefinition parse(String json) {
        try {
            return loader.parse(new ObjectMapper().readTree(json));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String journeyWithTerminalStatus(String status) {
        return """
                {"journeyKey":"t","startNodeId":"end",
                 "nodes":[{"id":"end","type":"terminal","action":"push","status":"%s"}]}
                """.formatted(status);
    }

    private static String journeyWithJoinPolicy(String policy) {
        // a/b are REAL nodes: the T2 load-time validator rejects dangling joinOn
        // references before the policy is even considered.
        return """
                {"journeyKey":"t","startNodeId":"p",
                 "nodes":[{"id":"p","type":"parallel","branches":["a","b"]},
                          {"id":"a","type":"task","capability":"kyc","next":["j"]},
                          {"id":"b","type":"task","capability":"bureau","next":["j"]},
                          {"id":"j","type":"join","joinOn":["a","b"],"policy":"%s","next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """.formatted(policy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aproved", "APPROVED", "cancelled", "declined", "done"})
    void unknownTerminalStatusRefusesToLoad(String typo) {
        assertThatThrownBy(() -> parse(journeyWithTerminalStatus(typo)))
                .as("a typo'd terminal status must fail at load — never default to APPROVED")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown status '" + typo + "'")
                .hasMessageContaining("fail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "rejected", "failed"})
    void theThreeContractStatusesLoad(String valid) {
        assertThat(parse(journeyWithTerminalStatus(valid)).node("end").status()).isEqualTo(valid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANY_OF", "any", "someOf", "quorum()", "quorum(x)"})
    void nonExecutableJoinPolicyRefusesToLoad(String policy) {
        assertThatThrownBy(() -> parse(journeyWithJoinPolicy(policy)))
                .as("a join policy the engine cannot execute must fail at load — never silently run as allOf")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy '" + policy + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"allOf", "anyOf", "quorum(1)", "quorum(2)"})
    void theT2JoinPoliciesLoad(String policy) {
        assertThat(parse(journeyWithJoinPolicy(policy)).node("j").joinPolicy()).isEqualTo(policy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"quorum(0)", "quorum(3)", "quorum(99)"})
    void outOfBoundsQuorumRefusesToLoad(String policy) {
        assertThatThrownBy(() -> parse(journeyWithJoinPolicy(policy)))
                .as("a quorum outside [1, |joinOn|] can never fire correctly — refuse at load")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of bounds");
    }

    // ---- T2 load-time validator: structural integrity -------------------------

    @Test
    void danglingReferencesRefuseToLoad() {
        String dangling = """
                {"journeyKey":"t","startNodeId":"a",
                 "nodes":[{"id":"a","type":"task","capability":"kyc","next":["ghost"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThatThrownBy(() -> parse(dangling))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing node 'ghost'");
    }

    @Test
    void branchWithoutDefaultRefusesToLoad() {
        String noDefault = """
                {"journeyKey":"t","startNodeId":"b",
                 "nodes":[{"id":"b","type":"branch","arms":[{"when":"context.x == '1'","next":"end"}]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThatThrownBy(() -> parse(noDefault))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no default arm");
    }

    @Test
    void permanentInRetryOnRefusesToLoad() {
        String retryPermanent = """
                {"journeyKey":"t","startNodeId":"a",
                 "nodes":[{"id":"a","type":"task","capability":"kyc",
                           "policies":{"retry":{"maxAttempts":3,
                             "backoff":{"type":"exponential","base":"PT1S","max":"PT10S"},
                             "retryOn":["PERMANENT"]}},
                           "next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThatThrownBy(() -> parse(retryPermanent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERMANENT is never retryable");
    }

    @Test
    void malformedBackoffDurationRefusesToLoad() {
        String badDuration = """
                {"journeyKey":"t","startNodeId":"a",
                 "nodes":[{"id":"a","type":"task","capability":"kyc",
                           "policies":{"retry":{"maxAttempts":3,
                             "backoff":{"type":"exponential","base":"2 seconds","max":"PT10S"}}},
                           "next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThatThrownBy(() -> parse(badDuration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a §7 duration");
    }

    @Test
    void bothAuthoredDurationFormsLoad() {
        String shorthandAndIso = """
                {"journeyKey":"t","startNodeId":"a",
                 "nodes":[{"id":"a","type":"task","capability":"kyc",
                           "policies":{"retry":{"maxAttempts":3,
                             "backoff":{"type":"exponential","base":"200ms","max":"PT10S"}}},
                           "next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThat(parse(shorthandAndIso).node("a").retrySpec().backoffBaseMillis()).isEqualTo(200);
        assertThat(parse(shorthandAndIso).node("a").retrySpec().backoffMaxMillis()).isEqualTo(10_000);
    }

    // ---- A6: schemaVersion checked at load ---------------------------------

    private static String journeyWithSchemaLine(String schemaLine) {
        return """
                {"journeyKey":"t","version":1,%s"startNodeId":"end",
                 "nodes":[{"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """.formatted(schemaLine);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 99})
    void unknownSchemaVersionRefusesToLoad(int future) {
        assertThatThrownBy(() -> parse(journeyWithSchemaLine("\"schemaVersion\":" + future + ",")))
                .as("a schema generation this engine doesn't understand must fail at load —"
                        + " half-parsing drops semantics silently")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schemaVersion " + future)
                .hasMessageContaining("refusing to load");
    }

    @Test
    void supportedAndLegacyUnstampedConfigsLoad() {
        assertThat(parse(journeyWithSchemaLine("\"schemaVersion\":"
                + JourneyDefinitionLoader.SUPPORTED_SCHEMA_VERSION + ",")).key()).isEqualTo("t");
        // No stamp = pre-A6 artifact already published in a registry: keeps running.
        assertThat(parse(journeyWithSchemaLine("")).key()).isEqualTo("t");
    }

    @Test
    void absentJoinPolicyStillLoads() {
        String noPolicy = """
                {"journeyKey":"t","startNodeId":"p",
                 "nodes":[{"id":"p","type":"parallel","branches":["a","b"]},
                          {"id":"a","type":"task","capability":"kyc","next":["j"]},
                          {"id":"b","type":"task","capability":"bureau","next":["j"]},
                          {"id":"j","type":"join","joinOn":["a","b"],"next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThat(parse(noPolicy).node("j").joinPolicy()).isNull();
    }
}

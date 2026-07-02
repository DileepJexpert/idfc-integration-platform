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
        return """
                {"journeyKey":"t","startNodeId":"j",
                 "nodes":[{"id":"j","type":"join","joinOn":["a","b"],"policy":"%s","next":["end"]},
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
    @ValueSource(strings = {"anyOf", "quorum(2)", "ANY_OF", "any"})
    void nonExecutableJoinPolicyRefusesToLoad(String policy) {
        assertThatThrownBy(() -> parse(journeyWithJoinPolicy(policy)))
                .as("a join policy T1 cannot execute must fail at load — never silently run as allOf")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy '" + policy + "'");
    }

    @Test
    void allOfAndAbsentJoinPolicyStillLoad() {
        assertThat(parse(journeyWithJoinPolicy("allOf")).node("j").joinPolicy()).isEqualTo("allOf");
        String noPolicy = """
                {"journeyKey":"t","startNodeId":"j",
                 "nodes":[{"id":"j","type":"join","joinOn":["a","b"],"next":["end"]},
                          {"id":"end","type":"terminal","action":"push","status":"completed"}]}
                """;
        assertThat(parse(noPolicy).node("j").joinPolicy()).isNull();
    }
}

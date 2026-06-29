package com.idfcfirstbank.integration.capabilities.scoring.domain.service;

import com.idfcfirstbank.integration.capabilities.scoring.domain.model.ScoringDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRuleTest {

    private final DecisionRule rule = new DecisionRule();

    static Stream<Arguments> cases() {
        return Stream.of(
                // bureauScore, negativeFlags, threshold, expectedDecision
                Arguments.of(780, List.of(), 700, ScoringDecision.APPROVED),
                Arguments.of(540, List.of(), 700, ScoringDecision.REJECTED),
                Arguments.of(700, List.of(), 700, ScoringDecision.APPROVED),          // exactly at threshold
                Arguments.of(820, List.of("FRAUD_HIT"), 700, ScoringDecision.REJECTED) // high but flagged
        );
    }

    @ParameterizedTest
    @MethodSource("cases")
    void decidesPerRule(int bureauScore, List<String> flags, int threshold, String expected) {
        ScoringDecision decision = rule.decide(bureauScore, flags, threshold);

        assertThat(decision.decision()).isEqualTo(expected);
        assertThat(decision.score()).isEqualTo(bureauScore);
        assertThat(decision.reasons()).isNotEmpty();
    }

    @Test
    void negativeFlagsAreCalledOutInReasons() {
        ScoringDecision decision = rule.decide(820, List.of("FRAUD_HIT"), 700);
        assertThat(decision.decision()).isEqualTo(ScoringDecision.REJECTED);
        assertThat(decision.reasons()).anyMatch(r -> r.contains("FRAUD_HIT"));
    }

    @Test
    void nullFlagsTreatedAsEmpty() {
        ScoringDecision decision = rule.decide(780, null, 700);
        assertThat(decision.decision()).isEqualTo(ScoringDecision.APPROVED);
    }
}

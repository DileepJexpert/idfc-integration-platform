package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    @Test
    void equalityOnAStringField() {
        Map<String, Object> ctx = Map.of("decision", "APPROVED");
        assertThat(evaluator.evaluate("decision == 'APPROVED'", ctx)).isTrue();
        assertThat(evaluator.evaluate("decision == 'REJECTED'", ctx)).isFalse();
        assertThat(evaluator.evaluate("decision != 'REJECTED'", ctx)).isTrue();
    }

    @Test
    void numericComparisons() {
        Map<String, Object> ctx = Map.of("score", 720);
        assertThat(evaluator.evaluate("score >= 700", ctx)).isTrue();
        assertThat(evaluator.evaluate("score < 700", ctx)).isFalse();
        assertThat(evaluator.evaluate("score > 720", ctx)).isFalse();
        assertThat(evaluator.evaluate("score <= 720", ctx)).isTrue();
    }

    @Test
    void missingFieldIsFalseyForEquality() {
        assertThat(evaluator.evaluate("decision == 'APPROVED'", Map.of())).isFalse();
    }

    @Test
    void unsupportedExpressionThrows() {
        assertThatThrownBy(() -> evaluator.evaluate("decision", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

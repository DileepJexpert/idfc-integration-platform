package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The §7 INPUT-MAPPING grammar (T2): object literal of key -> context path |
 * quoted literal | number | boolean. Same deliberate-subset stance as the
 * boolean grammar — anything outside it throws, never guesses.
 */
class ExpressionMappingTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    private final Map<String, Object> context = Map.of(
            "context", Map.of(
                    "loan", Map.of("id", "LN-9", "amount", 250000),
                    "docs", List.of(Map.of("ref", "D-1"))));

    @Test
    void mapsPathsLiteralsNumbersAndBooleans() {
        Map<String, Object> mapped = evaluator.evaluateMapping(
                "{ loanId: context.loan.id, mode: 'FULL', n: 7, ratio: 0.5, force: true }", context);
        assertThat(mapped).isEqualTo(Map.of(
                "loanId", "LN-9", "mode", "FULL", "n", 7L, "ratio", 0.5, "force", true));
    }

    @Test
    void pathsResolveArrayIndexing_andMissingPathsMapToNull() {
        Map<String, Object> mapped = evaluator.evaluateMapping(
                "{ docRef: context.docs[0].ref, ghost: context.nope.nothing }", context);
        assertThat(mapped.get("docRef")).isEqualTo("D-1");
        assertThat(mapped).containsKey("ghost");
        assertThat(mapped.get("ghost")).isNull();
    }

    @Test
    void quotedValuesWithCommasAndColonsStayLiteral() {
        Map<String, Object> mapped = evaluator.evaluateMapping(
                "{ note: 'a, b: c', key: \"x:y\" }", context);
        assertThat(mapped).isEqualTo(Map.of("note", "a, b: c", "key", "x:y"));
    }

    @Test
    void emptyMappingIsEmpty() {
        assertThat(evaluator.evaluateMapping("{ }", context)).isEmpty();
        assertThat(evaluator.evaluateMapping(null, context)).isEmpty();
    }

    @Test
    void malformedMappingsThrowInsteadOfGuessing() {
        assertThatThrownBy(() -> evaluator.evaluateMapping("loanId: x", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("object literal");
        assertThatThrownBy(() -> evaluator.evaluateMapping("{ loanId }", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without ':'");
        assertThatThrownBy(() -> evaluator.evaluateMapping("{ n: 12x }", context))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a number");
    }
}

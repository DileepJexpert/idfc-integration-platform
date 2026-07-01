package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The CEL-subset extensions the verification slice needs: {@code &&}/{@code ||} + array indexing. */
class ExpressionEvaluatorTest {

    private final ExpressionEvaluator evaluator = new ExpressionEvaluator();

    // context.vehicleRc.DATA.result[0].result.{rcStatus,blackListStatus}
    private static Map<String, Object> vehicleRcContext(String rcStatus, String blackList) {
        Map<String, Object> result = Map.of("result", Map.of("rcStatus", rcStatus, "blackListStatus", blackList));
        Map<String, Object> data = Map.of("result", List.of(result));
        Map<String, Object> vehicleRc = Map.of("ISSUCCESS", "True", "DATA", data);
        return Map.of("context", Map.of("vehicleRc", vehicleRc));
    }

    private static final String RC_PROCEED =
            "context.vehicleRc.ISSUCCESS == 'True' "
          + "&& context.vehicleRc.DATA.result[0].result.rcStatus == 'ACTIVE' "
          + "&& context.vehicleRc.DATA.result[0].result.blackListStatus == 'CLEAR'";

    @Test
    void compoundAndWithArrayIndexingProceedsWhenAllTrue() {
        assertThat(evaluator.evaluate(RC_PROCEED, vehicleRcContext("ACTIVE", "CLEAR"))).isTrue();
    }

    @Test
    void compoundAndFailsWhenAnyConjunctIsFalse() {
        assertThat(evaluator.evaluate(RC_PROCEED, vehicleRcContext("ACTIVE", "BLACKLISTED"))).isFalse();
        assertThat(evaluator.evaluate(RC_PROCEED, vehicleRcContext("INACTIVE", "CLEAR"))).isFalse();
    }

    @Test
    void orPicksEitherBranch() {
        Map<String, Object> ctx = Map.of("context", Map.of("a", "1"));
        assertThat(evaluator.evaluate("context.a == '9' || context.a == '1'", ctx)).isTrue();
        assertThat(evaluator.evaluate("context.a == '9' || context.a == '8'", ctx)).isFalse();
    }

    @Test
    void booleanAndNumericComparisonsStillWork() {
        Map<String, Object> ctx = Map.of("context", Map.of("disposable", false, "score", 850));
        assertThat(evaluator.evaluate("context.disposable == false", ctx)).isTrue();
        assertThat(evaluator.evaluate("context.score >= 700", ctx)).isTrue();
    }

    @Test
    void singleComparisonBackwardCompatibilityIsUnchanged() {
        Map<String, Object> ctx = Map.of("context", Map.of("scoring", Map.of("decision", "APPROVED")));
        assertThat(evaluator.evaluate("context.scoring.decision == 'APPROVED'", ctx)).isTrue();
        assertThat(evaluator.evaluate("context.scoring.decision == 'DECLINED'", ctx)).isFalse();
    }

    @Test
    void missingPathOrOutOfRangeIndexResolvesFalseNotError() {
        Map<String, Object> ctx = Map.of("context", Map.of("vehicleRc", Map.of("DATA", Map.of("result", List.of()))));
        assertThat(evaluator.evaluate("context.vehicleRc.DATA.result[0].result.rcStatus == 'ACTIVE'", ctx)).isFalse();
        assertThat(evaluator.evaluate("context.nope.missing == 'x'", ctx)).isFalse();
    }
}

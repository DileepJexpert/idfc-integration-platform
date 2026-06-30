package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import java.util.Map;

/**
 * Tiny, deterministic boolean expression evaluator for branch arms — demo-grade
 * and intentionally minimal. Supports a single binary comparison:
 *
 * <pre>{@code  <field> <op> <literal>   e.g.  decision == 'APPROVED'   score >= 700 }</pre>
 *
 * Operators: {@code == != >= <= > <}. The left side is a key resolved from the
 * run context (see {@link com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance#evaluationContext()}).
 * The right side is a quoted string or a number. {@code ==}/{@code !=} compare as
 * strings; the ordering operators parse both sides as numbers.
 *
 * <p>NOT a general expression language — if a journey needs richer logic that is
 * a deliberate extension, not a silent default.
 */
public final class ExpressionEvaluator {

    // Order matters: match the two-char operators before the one-char ones.
    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

    public boolean evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        for (String op : OPERATORS) {
            int idx = expression.indexOf(op);
            if (idx < 0) {
                continue;
            }
            String lhsKey = expression.substring(0, idx).trim();
            String rhsRaw = expression.substring(idx + op.length()).trim();
            Object lhsValue = resolvePath(lhsKey, context);
            return compare(lhsValue, op, rhsRaw);
        }
        throw new IllegalArgumentException("unsupported branch expression: " + expression);
    }

    /**
     * Resolve a dotted §7 path (e.g. {@code context.scoring.decision}) by walking
     * nested maps from the evaluation root. A missing segment resolves to null.
     */
    @SuppressWarnings("unchecked")
    private static Object resolvePath(String path, Map<String, Object> root) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> m) {
                current = ((Map<String, Object>) m).get(segment);
            } else {
                return null;
            }
        }
        return current;
    }

    private boolean compare(Object lhsValue, String op, String rhsRaw) {
        return switch (op) {
            case "==" -> stringOf(lhsValue).equals(unquote(rhsRaw));
            case "!=" -> !stringOf(lhsValue).equals(unquote(rhsRaw));
            case ">=" -> numberOf(lhsValue) >= Double.parseDouble(rhsRaw);
            case "<=" -> numberOf(lhsValue) <= Double.parseDouble(rhsRaw);
            case ">" -> numberOf(lhsValue) > Double.parseDouble(rhsRaw);
            case "<" -> numberOf(lhsValue) < Double.parseDouble(rhsRaw);
            default -> throw new IllegalArgumentException("unsupported operator: " + op);
        };
    }

    private static String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static double numberOf(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return Double.parseDouble(stringOf(value));
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.startsWith("'") && s.endsWith("'") || s.startsWith("\"") && s.endsWith("\""))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}

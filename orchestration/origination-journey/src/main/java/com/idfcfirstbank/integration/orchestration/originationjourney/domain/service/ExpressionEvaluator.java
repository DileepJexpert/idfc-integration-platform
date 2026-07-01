package com.idfcfirstbank.integration.orchestration.originationjourney.domain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tiny, deterministic boolean expression evaluator for branch arms — a CEL-compatible
 * SUBSET. Supports:
 *
 * <pre>{@code
 *   <field> <op> <literal>                     decision == 'APPROVED'   score >= 700
 *   <expr> && <expr> && ...                     ISSUCCESS == 'True' && rcStatus == 'ACTIVE'
 *   <expr> || <expr> || ...                     a == '1' || b == '2'
 *   dotted paths with array indexing            DATA.result[0].result.rcStatus
 * }</pre>
 *
 * Operators: {@code == != >= <= > <}, conjunction {@code &&} / disjunction {@code ||}
 * (AND binds tighter than OR; no parentheses). The left side is a path resolved from
 * the run context (nested maps + {@code [n]} list indexing). The right side is a quoted
 * string, a number, or {@code true}/{@code false}. {@code ==}/{@code !=} compare as
 * strings; ordering operators parse both sides as numbers.
 *
 * <p>NOT a general expression language — each addition here is a deliberate extension,
 * not a silent default (the verification slice needs {@code &&} + {@code [n]}).
 */
public final class ExpressionEvaluator {

    // Order matters: match the two-char operators before the one-char ones.
    private static final String[] OPERATORS = {"==", "!=", ">=", "<=", ">", "<"};

    public boolean evaluate(String expression, Map<String, Object> context) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        return evaluateOr(expression, context);
    }

    /** OR of AND-terms (OR is the lowest-precedence, top-level split). */
    private boolean evaluateOr(String expression, Map<String, Object> context) {
        for (String term : splitTopLevel(expression, "||")) {
            if (evaluateAnd(term, context)) {
                return true;
            }
        }
        return false;
    }

    /** AND of comparisons. */
    private boolean evaluateAnd(String expression, Map<String, Object> context) {
        for (String factor : splitTopLevel(expression, "&&")) {
            if (!evaluateComparison(factor, context)) {
                return false;
            }
        }
        return true;
    }

    /** A single binary comparison {@code <path> <op> <literal>}. */
    private boolean evaluateComparison(String expression, Map<String, Object> context) {
        String expr = expression.trim();
        for (String op : OPERATORS) {
            int idx = expr.indexOf(op);
            if (idx < 0) {
                continue;
            }
            String lhsKey = expr.substring(0, idx).trim();
            String rhsRaw = expr.substring(idx + op.length()).trim();
            Object lhsValue = resolvePath(lhsKey, context);
            return compare(lhsValue, op, rhsRaw);
        }
        throw new IllegalArgumentException("unsupported branch expression: " + expression);
    }

    /** Split on a top-level delimiter ({@code &&}/{@code ||}), ignoring delimiters inside quotes. */
    private static List<String> splitTopLevel(String expr, String delim) {
        List<String> parts = new ArrayList<>();
        boolean inSingle = false;
        boolean inDouble = false;
        int start = 0;
        int i = 0;
        while (i < expr.length()) {
            char c = expr.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            }
            if (!inSingle && !inDouble && expr.startsWith(delim, i)) {
                parts.add(expr.substring(start, i));
                i += delim.length();
                start = i;
                continue;
            }
            i++;
        }
        parts.add(expr.substring(start));
        return parts;
    }

    /**
     * Resolve a dotted §7 path (e.g. {@code context.vehicleRc.DATA.result[0].result.rcStatus})
     * by walking nested maps, with {@code [n]} list indexing per segment. A missing
     * segment (or out-of-range index) resolves to null.
     */
    private static Object resolvePath(String path, Map<String, Object> root) {
        Object current = root;
        for (String segment : path.split("\\.")) {
            current = resolveSegment(current, segment.trim());
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** Resolve one path segment: an optional map key followed by zero or more {@code [n]} indices. */
    @SuppressWarnings("unchecked")
    private static Object resolveSegment(Object current, String segment) {
        int bracket = segment.indexOf('[');
        String name = bracket < 0 ? segment : segment.substring(0, bracket);
        if (!name.isEmpty()) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = ((Map<String, Object>) m).get(name);
        }
        if (bracket >= 0) {
            for (String token : segment.substring(bracket).split("]")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (!t.startsWith("[")) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(t.substring(1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (!(current instanceof List<?> list) || index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
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

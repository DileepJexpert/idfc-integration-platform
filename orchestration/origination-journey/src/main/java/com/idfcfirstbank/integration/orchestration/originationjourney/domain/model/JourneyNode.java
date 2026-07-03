package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.List;

/**
 * A single §7 DAG node (Charter §2). One fat record covers every {@link NodeType}
 * (only the relevant fields are populated) so loading stays a straight JSON map.
 * Field names match the contract. Use the static factories to construct.
 */
public record JourneyNode(
        String id,
        NodeType type,
        String condition,
        // task
        String capability,
        String operation,
        String input,
        String output,
        String onFailure,
        String meterPool,
        Compensation compensation,
        RetrySpec retrySpec,
        CircuitBreakerSpec circuitBreakerSpec,
        boolean optional,
        List<String> next,
        // branch
        List<BranchArm> arms,
        String defaultNext,
        // parallel
        List<String> branches,
        // join
        List<String> joinOn,
        String joinPolicy,
        // wait
        String waitFor,
        String correlation,
        String timeout,
        String onTimeout,
        // timer
        String delay,
        String at,
        // terminal
        String action,
        List<String> emit,
        String status) {

    public JourneyNode {
        next = next == null ? List.of() : List.copyOf(next);
        arms = arms == null ? List.of() : List.copyOf(arms);
        branches = branches == null ? List.of() : List.copyOf(branches);
        joinOn = joinOn == null ? List.of() : List.copyOf(joinOn);
        emit = emit == null ? List.of() : List.copyOf(emit);
    }

    // ---- factories -----------------------------------------------------------

    public static JourneyNode task(String id, String condition, String capability, String operation,
                                   String input, String output, String onFailure, String meterPool,
                                   Compensation compensation, boolean optional, List<String> next) {
        return task(id, condition, capability, operation, input, output, onFailure, meterPool,
                compensation, null, null, optional, next);
    }

    /** T2 form: with parsed retry / circuit-breaker policies. */
    public static JourneyNode task(String id, String condition, String capability, String operation,
                                   String input, String output, String onFailure, String meterPool,
                                   Compensation compensation, RetrySpec retrySpec,
                                   CircuitBreakerSpec circuitBreakerSpec, boolean optional,
                                   List<String> next) {
        return new JourneyNode(id, NodeType.TASK, condition, capability, operation, input, output,
                onFailure, meterPool, compensation, retrySpec, circuitBreakerSpec, optional, next,
                List.of(), null, List.of(), List.of(), null, null, null, null, null, null, null, null,
                List.of(), null);
    }

    public static JourneyNode branch(String id, String condition, List<BranchArm> arms, String defaultNext) {
        return new JourneyNode(id, NodeType.BRANCH, condition, null, null, null, null, null, null, null,
                null, null, false, List.of(), arms, defaultNext, List.of(), List.of(), null, null, null,
                null, null, null, null, null, List.of(), null);
    }

    public static JourneyNode parallel(String id, String condition, List<String> branches) {
        return new JourneyNode(id, NodeType.PARALLEL, condition, null, null, null, null, null, null, null,
                null, null, false, List.of(), List.of(), null, branches, List.of(), null, null, null,
                null, null, null, null, null, List.of(), null);
    }

    public static JourneyNode join(String id, String condition, List<String> joinOn, String policy,
                                   List<String> next) {
        return new JourneyNode(id, NodeType.JOIN, condition, null, null, null, null, null, null, null,
                null, null, false, next, List.of(), null, List.of(), joinOn, policy, null, null, null,
                null, null, null, null, List.of(), null);
    }

    public static JourneyNode waitNode(String id, String condition, String waitFor, String correlation,
                                       String timeout, String onTimeout, String output, List<String> next) {
        return new JourneyNode(id, NodeType.WAIT, condition, null, null, null, output, null, null, null,
                null, null, false, next, List.of(), null, List.of(), List.of(), null, waitFor,
                correlation, timeout, onTimeout, null, null, null, List.of(), null);
    }

    public static JourneyNode timer(String id, String condition, String delay, String at, List<String> next) {
        return new JourneyNode(id, NodeType.TIMER, condition, null, null, null, null, null, null, null,
                null, null, false, next, List.of(), null, List.of(), List.of(), null, null, null, null,
                null, delay, at, null, List.of(), null);
    }

    /** Minimal parse for not-yet-executed kinds (human/foreach/subjourney): id + next. */
    public static JourneyNode passthrough(String id, NodeType type, String condition, List<String> next) {
        return new JourneyNode(id, type, condition, null, null, null, null, null, null, null,
                null, null, false, next, List.of(), null, List.of(), List.of(), null, null, null, null,
                null, null, null, null, List.of(), null);
    }

    public static JourneyNode terminal(String id, String action, List<String> emit, String status) {
        return new JourneyNode(id, NodeType.TERMINAL, null, null, null, null, null, null, null, null,
                null, null, false, List.of(), List.of(), null, List.of(), List.of(), null, null, null,
                null, null, null, null, action, emit, status);
    }

    // ---- helpers -------------------------------------------------------------

    public boolean isMetered() {
        return meterPool != null && !meterPool.isBlank();
    }

    /**
     * How many {@code joinOn} members must COMPLETE before this JOIN fires (T2):
     * {@code allOf}/absent → all of them; {@code anyOf} → 1; {@code quorum(n)} → n.
     * The loader validator guarantees the policy string is one of these shapes
     * and that a quorum is within bounds.
     */
    public int joinThreshold() {
        if (joinPolicy == null || "allOf".equals(joinPolicy)) {
            return joinOn.size();
        }
        if ("anyOf".equals(joinPolicy)) {
            return 1;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("^quorum\\((\\d+)\\)$").matcher(joinPolicy);
        if (m.matches()) {
            return Integer.parseInt(m.group(1));
        }
        // Unreachable behind the load-time validator; fail CLOSED if it ever isn't.
        throw new IllegalStateException("join '" + id + "' has unvalidated policy '" + joinPolicy + "'");
    }

    /** All forward routing targets, for predecessor/graph queries. */
    public List<String> successors() {
        return switch (type) {
            case TASK, JOIN, TIMER, WAIT, HUMAN, FOREACH, SUBJOURNEY -> {
                if (type == NodeType.WAIT && onTimeout != null) {
                    java.util.List<String> s = new java.util.ArrayList<>(next);
                    s.add(onTimeout);
                    yield s;
                }
                yield next;
            }
            case BRANCH -> {
                java.util.List<String> s = new java.util.ArrayList<>();
                for (BranchArm a : arms) s.add(a.next());
                if (defaultNext != null) s.add(defaultNext);
                yield s;
            }
            case PARALLEL -> branches;
            case TERMINAL -> List.of();
        };
    }
}

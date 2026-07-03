package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.RetrySpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T2 LOAD-TIME validation of a parsed {@link JourneyDefinition} — the engine
 * half of the authoring-time checks the registry's JourneyConfigValidator runs
 * (defense in depth: registry-bypassing classpath fixtures get the same gate).
 * Structural integrity (references, duplicates, branch defaults) plus the T2
 * policy bounds (quorum within joinOn, retry classes, breaker thresholds).
 * EVERY violation is collected and reported at once; any violation refuses the
 * load — publish-time, never mid-run.
 */
final class JourneyDefinitionValidator {

    private static final Pattern QUORUM = Pattern.compile("^quorum\\((\\d+)\\)$");
    /** onFailure keywords that are policies, not node references. */
    private static final Set<String> FAILURE_KEYWORDS = Set.of("compensate", "dlq", "fail");
    /** ErrorClass names a retry may act on; PERMANENT is never retryable. */
    private static final Set<String> RETRYABLE_CLASSES = Set.of("TRANSIENT", "AMBIGUOUS");

    private JourneyDefinitionValidator() {
    }

    static void validate(JourneyDefinition def) {
        List<String> violations = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JourneyNode node : def.nodes()) {
            if (!ids.add(node.id())) {
                violations.add("duplicate node id '" + node.id() + "'");
            }
        }

        if (!ids.contains(def.startNodeId())) {
            violations.add("startNodeId '" + def.startNodeId() + "' is not a node");
        }

        for (JourneyNode node : def.nodes()) {
            for (String target : node.successors()) {
                if (!ids.contains(target)) {
                    violations.add("node '" + node.id() + "' routes to missing node '" + target + "'");
                }
            }
            String onFailure = node.onFailure();
            if (onFailure != null && !FAILURE_KEYWORDS.contains(onFailure) && !ids.contains(onFailure)) {
                violations.add("node '" + node.id() + "' onFailure points at missing node '"
                        + onFailure + "'");
            }
            switch (node.type()) {
                case TASK -> validateTask(node, violations);
                case BRANCH -> {
                    if (node.arms().isEmpty()) {
                        violations.add("branch '" + node.id() + "' has no arms");
                    }
                    if (node.defaultNext() == null) {
                        violations.add("branch '" + node.id() + "' has no default arm (§9.5 mandatory)");
                    }
                }
                case JOIN -> validateJoin(node, ids, violations);
                default -> { }
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException("journey '" + def.key() + "' failed load-time validation ("
                    + violations.size() + " violation(s)): " + String.join("; ", violations));
        }
    }

    private static void validateTask(JourneyNode node, List<String> violations) {
        if (node.capability() == null || node.capability().isBlank()) {
            violations.add("task '" + node.id() + "' has no capability");
        }
        RetrySpec retry = node.retrySpec();
        if (retry != null) {
            if (retry.maxAttempts() < 1) {
                violations.add("task '" + node.id() + "' retry.maxAttempts must be >= 1");
            }
            for (String cls : retry.retryOn()) {
                if (!RETRYABLE_CLASSES.contains(cls)) {
                    violations.add("task '" + node.id() + "' retryOn contains '" + cls
                            + "' (allowed: " + RETRYABLE_CLASSES + " — PERMANENT is never retryable)");
                }
            }
        }
        if (node.circuitBreakerSpec() != null && node.circuitBreakerSpec().failureThreshold() < 1) {
            violations.add("task '" + node.id() + "' circuitBreaker.failureThreshold must be >= 1");
        }
    }

    private static void validateJoin(JourneyNode node, Set<String> ids, List<String> violations) {
        if (node.joinOn().isEmpty()) {
            violations.add("join '" + node.id() + "' has no joinOn members");
        }
        for (String member : node.joinOn()) {
            if (!ids.contains(member)) {
                violations.add("join '" + node.id() + "' waits on missing node '" + member + "'");
            }
        }
        String policy = node.joinPolicy();
        if (policy == null || "allOf".equals(policy) || "anyOf".equals(policy)) {
            return;
        }
        Matcher m = QUORUM.matcher(policy);
        if (!m.matches()) {
            violations.add("join '" + node.id() + "' has unsupported policy '" + policy
                    + "' (allOf | anyOf | quorum(n))");
            return;
        }
        int n = Integer.parseInt(m.group(1));
        if (n < 1 || n > node.joinOn().size()) {
            violations.add("join '" + node.id() + "' quorum(" + n + ") is out of bounds for "
                    + node.joinOn().size() + " joinOn member(s) (1 <= n <= |joinOn|)");
        }
    }
}

package com.idfcfirstbank.integration.platform.journeyregistry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.ValidationIssue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE authoritative server-side §7 graph validator (the docs promised "the
 * engine re-validates authoritatively" — this is where it becomes true; the
 * designer's DagValidator mirrors these rules client-side for live UX).
 * Structural rules only — pure JsonNode, no engine types.
 *
 * <p>Issue codes reuse the designer's ValidationCode names where the rules
 * overlap (emptyDag, duplicateNodeId, noStartNode, startNodeMissing,
 * danglingEdge, branchNoDefault, unreachableNode) and extend the shared
 * vocabulary for parse-level gates the designer enforces by construction
 * (unknownNodeType, invalidTerminalStatus, unsupportedJoinPolicy).
 */
public class JourneyConfigValidator {

    private static final Set<String> NODE_TYPES = Set.of(
            "task", "branch", "parallel", "join", "wait", "timer",
            "human", "foreach", "subjourney", "terminal");
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "rejected", "failed");
    /** onFailure keywords that are policies, not node references. */
    private static final Set<String> FAILURE_KEYWORDS = Set.of("compensate", "dlq", "fail");

    public List<ValidationIssue> validate(JsonNode root) {
        List<ValidationIssue> issues = new ArrayList<>();

        JsonNode nodes = root.path("nodes");
        if (!nodes.isArray() || nodes.isEmpty()) {
            issues.add(ValidationIssue.error("emptyDag", "the journey has no nodes", null));
            return issues;
        }

        // -- ids + types ------------------------------------------------------
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode n : nodes) {
            String id = text(n, "id");
            if (id == null) {
                issues.add(ValidationIssue.error("duplicateNodeId", "a node has no id", null));
                continue;
            }
            if (byId.put(id, n) != null) {
                issues.add(ValidationIssue.error("duplicateNodeId", "duplicate node id '" + id + "'", id));
            }
            String type = text(n, "type");
            if (type == null || !NODE_TYPES.contains(type)) {
                issues.add(ValidationIssue.error("unknownNodeType",
                        "node '" + id + "' has unknown type '" + type + "'", id));
            }
        }

        // -- start node -------------------------------------------------------
        String start = text(root, "startNodeId");
        if (start == null || start.isBlank()) {
            issues.add(ValidationIssue.error("noStartNode", "startNodeId is not set", null));
        } else if (!byId.containsKey(start)) {
            issues.add(ValidationIssue.error("startNodeMissing",
                    "startNodeId '" + start + "' is not a node", null));
        }

        // -- per-node rules + edge integrity ----------------------------------
        for (Map.Entry<String, JsonNode> e : byId.entrySet()) {
            String id = e.getKey();
            JsonNode n = e.getValue();
            String type = text(n, "type");

            for (String target : successors(n)) {
                if (!byId.containsKey(target)) {
                    issues.add(ValidationIssue.error("danglingEdge",
                            "node '" + id + "' points at missing node '" + target + "'", id));
                }
            }
            String onFailure = text(n, "onFailure");
            if (onFailure != null && !FAILURE_KEYWORDS.contains(onFailure) && !byId.containsKey(onFailure)) {
                issues.add(ValidationIssue.error("danglingEdge",
                        "node '" + id + "' onFailure points at missing node '" + onFailure + "'", id));
            }

            if ("branch".equals(type)) {
                if (!n.path("arms").isArray() || n.path("arms").isEmpty()) {
                    issues.add(ValidationIssue.error("branchNoDefault",
                            "branch '" + id + "' has no arms", id));
                }
                if (text(n, "default") == null) {
                    issues.add(ValidationIssue.error("branchNoDefault",
                            "branch '" + id + "' has no default arm (§9.5)", id));
                }
            }
            if ("terminal".equals(type)) {
                String status = text(n, "status");
                if (status != null && !TERMINAL_STATUSES.contains(status)) {
                    issues.add(ValidationIssue.error("invalidTerminalStatus",
                            "terminal '" + id + "' has unknown status '" + status
                                    + "' (allowed: " + TERMINAL_STATUSES + ") — an unknown status must"
                                    + " fail closed, never default to APPROVED", id));
                }
            }
            if ("join".equals(type)) {
                String policy = text(n, "policy");
                if (policy != null && !"allOf".equals(policy)) {
                    issues.add(ValidationIssue.error("unsupportedJoinPolicy",
                            "join '" + id + "' declares policy '" + policy
                                    + "' which the current engine tier cannot execute (only allOf)", id));
                }
                if (!n.path("joinOn").isArray() || n.path("joinOn").isEmpty()) {
                    issues.add(ValidationIssue.error("joinOnUnknownPredecessor",
                            "join '" + id + "' has no joinOn members", id));
                } else {
                    for (JsonNode member : n.path("joinOn")) {
                        if (!byId.containsKey(member.asText())) {
                            issues.add(ValidationIssue.error("joinOnUnknownPredecessor",
                                    "join '" + id + "' waits on missing node '" + member.asText() + "'", id));
                        }
                    }
                }
            }
        }

        // -- reachability (only meaningful once the graph is structurally sound)
        if (issues.stream().noneMatch(ValidationIssue::isError) && start != null) {
            Set<String> reachable = reachableFrom(start, byId);
            for (String id : byId.keySet()) {
                if (!reachable.contains(id)) {
                    issues.add(ValidationIssue.warning("unreachableNode",
                            "node '" + id + "' is not reachable from the start node", id));
                }
            }
            boolean reachesTerminal = reachable.stream()
                    .anyMatch(id -> "terminal".equals(text(byId.get(id), "type")));
            if (!reachesTerminal) {
                issues.add(ValidationIssue.error("branchArmDeadEnd",
                        "no terminal node is reachable from the start node", start));
            }
        }
        return issues;
    }

    /** Forward routing targets per node kind (mirrors JourneyNode.successors()). */
    private static List<String> successors(JsonNode n) {
        List<String> out = new ArrayList<>();
        switch (String.valueOf(text(n, "type"))) {
            case "branch" -> {
                for (JsonNode arm : n.path("arms")) {
                    addText(out, arm, "next");
                }
                addText(out, n, "default");
            }
            case "parallel" -> n.path("branches").forEach(b -> out.add(b.asText()));
            // joinOn members are PREDECESSORS (checked in the join rule), not routes.
            case "join" -> n.path("next").forEach(s -> out.add(s.asText()));
            case "terminal" -> { /* no successors */ }
            default -> {
                n.path("next").forEach(s -> out.add(s.asText()));
                addText(out, n, "onTimeout");
            }
        }
        return out;
    }

    private static Set<String> reachableFrom(String start, Map<String, JsonNode> byId) {
        Set<String> seen = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            String id = stack.pop();
            if (!seen.add(id)) {
                continue;
            }
            JsonNode n = byId.get(id);
            if (n == null) {
                continue;
            }
            for (String s : successors(n)) {
                if (byId.containsKey(s)) {
                    stack.push(s);
                }
            }
            String onFailure = text(n, "onFailure");
            if (onFailure != null && byId.containsKey(onFailure)) {
                stack.push(onFailure);
            }
        }
        return seen;
    }

    private static void addText(List<String> out, JsonNode n, String field) {
        String v = text(n, field);
        if (v != null) {
            out.add(v);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.CircuitBreakerSpec;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.Compensation;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.RetrySpec;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses the LOCKED §7 journey config JSON (the exact artifact the DAG Designer's
 * ConfigSerializer emits — Charter §7, schema v2) into a domain
 * {@link JourneyDefinition}. The full grammar parses; the engine executes the T1
 * subset. {@code layout}, {@code pools}, and {@code context} are authoring /
 * future-tier metadata and ignored by the T1 engine.
 */
public class JourneyDefinitionLoader {

    private final ObjectMapper objectMapper;

    public JourneyDefinitionLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JourneyDefinition loadFromClasspath(String resourcePath) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("journey contract not on classpath: " + resourcePath);
            }
            return parse(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read journey contract: " + resourcePath, e);
        }
    }

    /** The §7 DSL generation this loader understands (A6, mirrors ConfigSerializer.schemaVersion). */
    public static final int SUPPORTED_SCHEMA_VERSION = 2;

    public JourneyDefinition parse(JsonNode root) {
        // A6: schemaVersion checked at load. A config stamped with a generation
        // this loader doesn't understand must REFUSE to load — half-parsing a
        // future grammar silently drops semantics mid-lending-decision. A config
        // with NO stamp is a pre-A6 legacy artifact (already-published configs
        // keep running) and is treated as the supported generation.
        JsonNode declared = root.get("schemaVersion");
        if (declared != null && !declared.isNull() && declared.asInt() != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("journey config declares schemaVersion " + declared.asInt()
                    + " but this engine understands only " + SUPPORTED_SCHEMA_VERSION
                    + " — refusing to load (fail closed, A6)");
        }
        String key = text(root, "journeyKey");
        if (key == null) {
            key = text(root, "key"); // tolerate the bare key field
        }
        String startNodeId = text(root, "startNodeId");
        // The published version this artifact IS — the pin unit. Registry-stamped
        // configs always carry it; a bare classpath fixture defaults to 1.
        int version = root.path("version").asInt(1);
        List<JourneyNode> nodes = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.add(parseNode(n));
        }
        if (key == null || startNodeId == null || nodes.isEmpty()) {
            throw new IllegalStateException("journey contract missing journeyKey/startNodeId/nodes");
        }
        JourneyDefinition def = new JourneyDefinition(key, version, startNodeId, nodes);
        // T2 load-time validation: structural integrity + policy bounds. A config
        // that would misbehave mid-run must refuse to LOAD (publish-time, not with
        // an applicant in flight).
        JourneyDefinitionValidator.validate(def);
        return def;
    }

    private JourneyNode parseNode(JsonNode n) {
        NodeType type = NodeType.valueOf(text(n, "type").toUpperCase());
        String id = text(n, "id");
        String condition = text(n, "condition");
        return switch (type) {
            case TASK -> JourneyNode.task(id, condition, text(n, "capability"), text(n, "operation"),
                    text(n, "input"), text(n, "output"), text(n, "onFailure"), meterPool(n),
                    compensation(n), retrySpec(id, n), circuitBreakerSpec(id, n),
                    n.path("optional").asBoolean(false), stringList(n, "next"));
            case BRANCH -> JourneyNode.branch(id, condition, arms(n), text(n, "default"));
            case PARALLEL -> JourneyNode.parallel(id, condition, stringList(n, "branches"));
            case JOIN -> JourneyNode.join(id, condition, stringList(n, "joinOn"),
                    validJoinPolicy(id, text(n, "policy")), stringList(n, "next"));
            case WAIT -> JourneyNode.waitNode(id, condition, text(n, "waitFor"), text(n, "correlation"),
                    text(n, "timeout"), text(n, "onTimeout"), text(n, "output"), stringList(n, "next"));
            case TIMER -> JourneyNode.timer(id, condition, text(n, "delay"), text(n, "at"),
                    stringList(n, "next"));
            case HUMAN, FOREACH, SUBJOURNEY ->
                    JourneyNode.passthrough(id, type, condition, stringList(n, "next"));
            case TERMINAL -> JourneyNode.terminal(id, text(n, "action"), stringList(n, "emit"),
                    validTerminalStatus(id, text(n, "status")));
        };
    }

    // ---- fail-closed enum gates (Phase 3, §C) ---------------------------------
    // The single most consequential output of the platform is the terminal
    // decision. A typo'd status must NEVER load and silently approve; a join
    // policy this engine tier cannot execute must NEVER load and silently run
    // as allOf. Reject at load — publish-time, not mid-run with an applicant
    // in flight.

    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "rejected", "failed");
    private static final java.util.regex.Pattern QUORUM =
            java.util.regex.Pattern.compile("^quorum\\((\\d+)\\)$");

    private static String validTerminalStatus(String nodeId, String status) {
        if (status != null && !TERMINAL_STATUSES.contains(status)) {
            throw new IllegalStateException("terminal '" + nodeId + "' declares unknown status '" + status
                    + "' (allowed: " + TERMINAL_STATUSES + ") — refusing to load: an unknown status must fail"
                    + " closed, never default to an APPROVED decision");
        }
        return status;
    }

    /**
     * T2 executes {@code allOf} / {@code anyOf} / {@code quorum(n)}. Anything
     * else still refuses to load — running an unknown policy silently as allOf
     * would change the journey's semantics. Quorum BOUNDS (1 ≤ n ≤ |joinOn|)
     * are checked by the {@link JourneyDefinitionValidator} where the joinOn
     * list is in scope.
     */
    private static String validJoinPolicy(String nodeId, String policy) {
        if (policy == null || "allOf".equals(policy) || "anyOf".equals(policy)
                || QUORUM.matcher(policy).matches()) {
            return policy;
        }
        throw new IllegalStateException("join '" + nodeId + "' declares policy '" + policy
                + "' which engine tier T2 cannot execute (allOf | anyOf | quorum(n)) — refusing"
                + " to load: running it silently as allOf would change the journey's semantics");
    }

    // ---- T2 policy parsing ------------------------------------------------------

    private static RetrySpec retrySpec(String nodeId, JsonNode n) {
        JsonNode r = n.path("policies").path("retry");
        if (r.isMissingNode()) {
            return null;
        }
        int maxAttempts = r.path("maxAttempts").asInt(1);
        JsonNode backoff = r.path("backoff");
        long base = backoff.isMissingNode() ? 0 : durationMillis(nodeId, "backoff.base", text(backoff, "base"));
        long max = backoff.isMissingNode() ? 0 : durationMillis(nodeId, "backoff.max", text(backoff, "max"));
        Set<String> retryOn = new java.util.LinkedHashSet<>();
        for (JsonNode c : r.path("retryOn")) {
            retryOn.add(c.asText());
        }
        return new RetrySpec(maxAttempts, base, max, r.path("jitter").asBoolean(false), retryOn);
    }

    private static CircuitBreakerSpec circuitBreakerSpec(String nodeId, JsonNode n) {
        JsonNode cb = n.path("policies").path("circuitBreaker");
        if (cb.isMissingNode()) {
            return null;
        }
        double rawThreshold = cb.path("failureThreshold").asDouble(0);
        if (rawThreshold < 1 || rawThreshold != Math.floor(rawThreshold)) {
            throw new IllegalStateException("task '" + nodeId + "' circuitBreaker.failureThreshold must be"
                    + " an integral count >= 1 (consecutive failures), got " + rawThreshold
                    + " — refusing to load");
        }
        long open = durationMillis(nodeId, "circuitBreaker.openDuration", text(cb, "openDuration"));
        return new CircuitBreakerSpec((int) rawThreshold, open, cb.path("halfOpenTrial").asInt(1));
    }

    private static final java.util.regex.Pattern SHORTHAND_DURATION =
            java.util.regex.Pattern.compile("^(\\d+)\\s*(ms|s|m|h)$");

    /**
     * §7 durations come in two authored forms — the shipped journeys' shorthand
     * ({@code 200ms}, {@code 5s}, {@code 2m}, {@code 1h}) and ISO-8601
     * ({@code PT2S}). Anything else refuses to load.
     */
    private static long durationMillis(String nodeId, String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("task '" + nodeId + "' declares policies with a missing '"
                    + field + "' duration — refusing to load");
        }
        String trimmed = raw.trim();
        java.util.regex.Matcher m = SHORTHAND_DURATION.matcher(trimmed);
        if (m.matches()) {
            long value = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> value;
                case "s" -> value * 1_000;
                case "m" -> value * 60_000;
                default -> value * 3_600_000; // "h"
            };
        }
        try {
            return java.time.Duration.parse(trimmed).toMillis();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("task '" + nodeId + "' policy '" + field + "' is not a §7"
                    + " duration ('200ms'/'5s'/'2m'/'1h' or ISO-8601 'PT2S'): '" + raw
                    + "' — refusing to load");
        }
    }

    private List<BranchArm> arms(JsonNode n) {
        List<BranchArm> arms = new ArrayList<>();
        for (JsonNode a : n.path("arms")) {
            arms.add(new BranchArm(text(a, "when"), text(a, "next")));
        }
        return arms;
    }

    private static String meterPool(JsonNode n) {
        JsonNode meter = n.path("policies").path("meter");
        return meter.isMissingNode() ? null : text(meter, "pool");
    }

    private static Compensation compensation(JsonNode n) {
        JsonNode c = n.get("compensation");
        if (c == null || c.isNull()) {
            return null;
        }
        return new Compensation(text(c, "operation"), text(c, "input"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : node.path(field)) {
            out.add(e.asText());
        }
        return out;
    }
}

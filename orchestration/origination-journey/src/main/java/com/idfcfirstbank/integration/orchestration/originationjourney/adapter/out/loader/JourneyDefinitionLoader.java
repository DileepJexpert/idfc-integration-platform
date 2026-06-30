package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.Compensation;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

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

    public JourneyDefinition parse(JsonNode root) {
        String key = text(root, "journeyKey");
        if (key == null) {
            key = text(root, "key"); // tolerate the bare key field
        }
        String startNodeId = text(root, "startNodeId");
        List<JourneyNode> nodes = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.add(parseNode(n));
        }
        if (key == null || startNodeId == null || nodes.isEmpty()) {
            throw new IllegalStateException("journey contract missing journeyKey/startNodeId/nodes");
        }
        return new JourneyDefinition(key, startNodeId, nodes);
    }

    private JourneyNode parseNode(JsonNode n) {
        NodeType type = NodeType.valueOf(text(n, "type").toUpperCase());
        String id = text(n, "id");
        String condition = text(n, "condition");
        return switch (type) {
            case TASK -> JourneyNode.task(id, condition, text(n, "capability"), text(n, "operation"),
                    text(n, "input"), text(n, "output"), text(n, "onFailure"), meterPool(n),
                    compensation(n), n.path("optional").asBoolean(false), stringList(n, "next"));
            case BRANCH -> JourneyNode.branch(id, condition, arms(n), text(n, "default"));
            case PARALLEL -> JourneyNode.parallel(id, condition, stringList(n, "branches"));
            case JOIN -> JourneyNode.join(id, condition, stringList(n, "joinOn"), text(n, "policy"),
                    stringList(n, "next"));
            case WAIT -> JourneyNode.waitNode(id, condition, text(n, "waitFor"), text(n, "correlation"),
                    text(n, "timeout"), text(n, "onTimeout"), text(n, "output"), stringList(n, "next"));
            case TIMER -> JourneyNode.timer(id, condition, text(n, "delay"), text(n, "at"),
                    stringList(n, "next"));
            case HUMAN, FOREACH, SUBJOURNEY ->
                    JourneyNode.passthrough(id, type, condition, stringList(n, "next"));
            case TERMINAL -> JourneyNode.terminal(id, text(n, "action"), stringList(n, "emit"),
                    text(n, "status"));
        };
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

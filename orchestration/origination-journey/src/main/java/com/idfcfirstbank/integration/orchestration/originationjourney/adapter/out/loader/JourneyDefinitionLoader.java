package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.BranchArm;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyNode;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.NodeType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the LOCKED journey config JSON (the exact artifact the DAG Designer's
 * ConfigSerializer emits — Schema Lock) into a domain {@link JourneyDefinition}.
 * Field names are read EXACTLY as the frontend emits them ({@code type, id,
 * capabilityKey, next, joinOn, meter, compensation, optional, arms, expression,
 * startNodeId}); {@code layout} is authoring metadata and ignored.
 *
 * <p>This is the engine half of the contract lock: if the fixture cannot be
 * parsed into a valid definition, the schema is MISALIGNED — fail loud, do not
 * paper over it.
 */
public class JourneyDefinitionLoader {

    private final ObjectMapper objectMapper;

    public JourneyDefinitionLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Load from a classpath resource, e.g. {@code journeys/loan-origination.journey.json}. */
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
        String key = text(root, "key");
        String startNodeId = text(root, "startNodeId");
        List<JourneyNode> nodes = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.add(parseNode(n));
        }
        if (key == null || startNodeId == null || nodes.isEmpty()) {
            throw new IllegalStateException("journey contract missing key/startNodeId/nodes");
        }
        return new JourneyDefinition(key, startNodeId, nodes);
    }

    private JourneyNode parseNode(JsonNode n) {
        NodeType type = NodeType.valueOf(text(n, "type").toUpperCase());
        String id = text(n, "id");
        return switch (type) {
            case TASK -> new JourneyNode(id, type, text(n, "capabilityKey"),
                    stringList(n, "next"), stringList(n, "joinOn"),
                    text(n, "meter"), text(n, "compensation"),
                    n.path("optional").asBoolean(false), List.of(), null, List.of());
            case BRANCH -> new JourneyNode(id, type, null, List.of(), stringList(n, "joinOn"),
                    null, null, false, arms(n), null, List.of());
            case TERMINAL -> new JourneyNode(id, type, null, List.of(), List.of(),
                    null, null, false, List.of(), text(n, "action"), stringList(n, "emit"));
        };
    }

    private List<BranchArm> arms(JsonNode n) {
        List<BranchArm> arms = new ArrayList<>();
        for (JsonNode a : n.path("arms")) {
            arms.add(new BranchArm(text(a, "expression"), text(a, "next")));
        }
        return arms;
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

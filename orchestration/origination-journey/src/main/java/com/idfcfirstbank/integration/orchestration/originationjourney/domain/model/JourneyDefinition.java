package com.idfcfirstbank.integration.orchestration.originationjourney.domain.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * An immutable, parsed journey definition (the locked
 * {@code loan-origination.journey.json}, layout dropped). Framework-free; the
 * JSON parsing lives in a loader adapter, this is the domain shape the engine
 * walks.
 */
public record JourneyDefinition(String key, String startNodeId, List<JourneyNode> nodes) {

    public JourneyDefinition {
        nodes = List.copyOf(nodes);
    }

    private Map<String, JourneyNode> byId() {
        return nodes.stream().collect(Collectors.toMap(JourneyNode::id, Function.identity()));
    }

    public JourneyNode node(String id) {
        JourneyNode n = byId().get(id);
        if (n == null) {
            throw new IllegalArgumentException("journey '" + key + "' has no node '" + id + "'");
        }
        return n;
    }

    public JourneyNode startNode() {
        return node(startNodeId);
    }

    /** Node ids that list {@code nodeId} among their successors (its predecessors). */
    public List<String> predecessorsOf(String nodeId) {
        return nodes.stream()
                .filter(n -> n.successors().contains(nodeId))
                .map(JourneyNode::id)
                .toList();
    }
}

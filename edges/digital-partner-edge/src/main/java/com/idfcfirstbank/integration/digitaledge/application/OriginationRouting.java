package com.idfcfirstbank.integration.digitaledge.application;

import java.util.Optional;

/**
 * Resolves the SAME origination topic the SFDC edge uses for a businessLine
 * ({@code type}) — config-as-data. Both edges route the same type to the same
 * topic, so the engine's single consumer handles either channel.
 */
public interface OriginationRouting {
    Optional<String> topicFor(String type);
}

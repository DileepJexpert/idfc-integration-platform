package com.idfcfirstbank.integration.orchestration.originationjourney.domain.port;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;

import java.util.List;
import java.util.Optional;

/**
 * OUT port: where journey definitions come from. Exactly ONE source is active —
 * the journey-registry service (production shape: publish in the Designer, the
 * engine runs it) or the classpath JAR (explicit Docker-free bootstrap fallback,
 * flagged at startup). Never both: a silent dual source of truth is how config
 * drift becomes an incident.
 */
public interface JourneySource {

    /**
     * Every journey's CURRENTLY-published definition (bootstrap + refresh read).
     * Throwing here at bootstrap fails engine startup — deliberately (an engine
     * that consumes origination events with no journeys would fail every one).
     */
    List<JourneyDefinition> loadCurrent();

    /**
     * A specific once-published version — the pinned in-flight fetch. Empty means
     * the source can prove it never published (permanent); a transient fault
     * (registry unreachable) must THROW instead, so the triggering record is
     * redelivered and retried rather than the run mis-resolved.
     */
    Optional<JourneyDefinition> load(String journeyKey, int version);

    /** For startup/refresh logs: which source of truth is active. */
    String describe();
}

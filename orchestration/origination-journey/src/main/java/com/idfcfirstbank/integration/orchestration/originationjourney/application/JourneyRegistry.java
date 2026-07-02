package com.idfcfirstbank.integration.orchestration.originationjourney.application;

import com.idfcfirstbank.integration.orchestration.originationjourney.domain.error.UnroutableTypeException;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDefinition;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The engine's view of the journey catalog. Three rules, each a deliberate A2
 * decision:
 *
 * <ol>
 *   <li><b>Routing is explicit and fail-closed.</b> An inbound {@code type}
 *       resolves ONLY through the configured {@code type-to-journey} map; there
 *       is no default-journey fallback (that fallback is how an account-creation
 *       event used to run the loan-origination DAG). Unmapped or unloadable
 *       routes throw {@link UnroutableTypeException} — the Kafka adapter sends
 *       those to the DLQ as poison.</li>
 *   <li><b>In-flight runs are version-pinned.</b> A run resolves the EXACT
 *       {@code key@version} it started on for every later hop; the currently-
 *       published snapshot only decides what NEW runs start on. Historical
 *       versions lazy-load from the source and are cached (published versions
 *       are immutable, so the cache can never go stale).</li>
 *   <li><b>Failure behavior is chosen, not inherited.</b> Bootstrap failure =
 *       refuse to start (an engine with no journeys would fail every consumed
 *       event). Refresh failure = keep serving the last-known snapshot and warn
 *       (a transient registry blip must not take down a running engine). A
 *       pinned fetch failure = throw (the triggering record redelivers and
 *       retries); only a PROVEN never-published answer fails the run.</li>
 * </ol>
 */
public class JourneyRegistry {

    private static final Logger log = LoggerFactory.getLogger(JourneyRegistry.class);

    private final JourneySource source;
    private final Map<String, String> typeToKey;

    /** The currently-published snapshot (immutable; swapped whole on refresh). */
    private volatile Map<String, JourneyDefinition> current = Map.of();
    /** Pinned historical versions ({@code key:version}); published = immutable, so never stale. */
    private final ConcurrentHashMap<String, JourneyDefinition> pinned = new ConcurrentHashMap<>();

    public JourneyRegistry(JourneySource source, Map<String, String> typeToKey) {
        this.source = source;
        this.typeToKey = typeToKey == null ? Map.of() : Map.copyOf(typeToKey);
    }

    /**
     * Load the initial snapshot. FAIL CLOSED on any failure or an empty catalog:
     * the engine must not start consuming origination events it cannot run.
     */
    public void bootstrap() {
        List<JourneyDefinition> defs;
        try {
            defs = source.loadCurrent();
        } catch (RuntimeException e) {
            throw new IllegalStateException("journey bootstrap from " + source.describe()
                    + " failed — refusing to start: an engine without journeys would fail every"
                    + " consumed origination event; start the registry (or fix the source) first", e);
        }
        if (defs.isEmpty()) {
            throw new IllegalStateException("journey bootstrap from " + source.describe()
                    + " returned an EMPTY catalog — refusing to start (publish at least one journey)");
        }
        swap(defs);
        log.info("journey.catalog.bootstrapped source={} journeys={}", source.describe(), describeCurrent());
    }

    /**
     * Re-read the published snapshot (scheduled in registry mode). A failure
     * keeps the last-known-good snapshot serving — deliberately: a registry blip
     * must not take down a running engine; the warn line is the operator signal.
     */
    public void refresh() {
        Map<String, JourneyDefinition> before = current;
        try {
            List<JourneyDefinition> defs = source.loadCurrent();
            if (defs.isEmpty()) {
                log.warn("journey.catalog.refresh source={} returned an empty catalog — keeping the"
                        + " last-known snapshot ({} journeys)", source.describe(), before.size());
                return;
            }
            swap(defs);
            if (!current.equals(before)) {
                log.info("journey.catalog.refreshed source={} journeys={}", source.describe(), describeCurrent());
            }
        } catch (RuntimeException e) {
            log.warn("journey.catalog.refresh source={} failed — keeping the last-known snapshot"
                    + " ({} journeys): {}", source.describe(), before.size(), e.toString());
        }
    }

    /**
     * Resolve the journey a NEW run of this {@code type} starts on (the current
     * published version). No fallback — see class contract.
     */
    public JourneyDefinition resolveForType(String type) {
        String key = typeToKey.get(type);
        if (key == null) {
            throw new UnroutableTypeException("no type-to-journey route for type '" + type
                    + "' (configured types: " + typeToKey.keySet() + ") — refusing to guess:"
                    + " add the idfc.engine.type-to-journey row for it");
        }
        JourneyDefinition def = current.get(key);
        if (def == null) {
            throw new UnroutableTypeException("type '" + type + "' routes to journey '" + key
                    + "' which is not published/loaded from " + source.describe()
                    + " (available: " + current.keySet() + ")");
        }
        return def;
    }

    /**
     * Resolve the EXACT definition an in-flight run is pinned to. Never
     * substitutes a different version: a cache/current miss lazy-fetches from
     * the source, a transient source failure propagates (redeliver-and-retry),
     * and a proven never-published answer fails loudly.
     *
     * <p>{@code version <= 0} marks a legacy pre-pinning instance (persisted
     * before A2): it resolves to the current version with a warn — the one
     * migration-window exception to strict pinning.
     */
    public JourneyDefinition definitionFor(String key, int version) {
        if (version <= 0) {
            JourneyDefinition cur = current.get(key);
            if (cur == null) {
                throw new IllegalStateException("legacy (unpinned) run of journey '" + key
                        + "' cannot resolve: journey not in the current catalog " + current.keySet());
            }
            log.warn("journey.resolve.legacy key={} — instance predates version pinning; resuming"
                    + " on current v{}", key, cur.version());
            return cur;
        }
        JourneyDefinition cur = current.get(key);
        if (cur != null && cur.version() == version) {
            return cur;
        }
        return pinned.computeIfAbsent(key + ":" + version, k ->
                source.load(key, version).orElseThrow(() -> new IllegalStateException(
                        "pinned journey " + key + "@v" + version + " is not servable from "
                                + source.describe() + " — it was never published there. In-flight runs"
                                + " survive publishes only with the registry source; a classpath JAR"
                                + " cannot serve historical versions")));
    }

    private void swap(List<JourneyDefinition> defs) {
        Map<String, JourneyDefinition> next = new LinkedHashMap<>();
        for (JourneyDefinition d : defs) {
            next.put(d.key(), d);
        }
        current = Map.copyOf(next);
    }

    private String describeCurrent() {
        return current.values().stream()
                .map(d -> d.key() + "@v" + d.version())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}

package com.idfcfirstbank.integration.platform.journeyregistry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException.Kind;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.ValidationIssue;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.VersionStatus;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.port.JourneyRegistryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * The maker-checker lifecycle, enforced SERVER-SIDE (the designer's RoleGate is
 * UX; this is the rule):
 *
 * <pre>
 *   createDraft(author)  DRAFT            — one editable version per journey (store-atomic)
 *   saveDraft            DRAFT only       — published/rejected versions are immutable
 *   submit               DRAFT -> PENDING — only after the §7 graph validates clean
 *   approve(actor)       PENDING -> PUBLISHED, actor != author (403), moves the pointer
 *   reject(actor)        PENDING -> REJECTED, actor != author (403)
 * </pre>
 *
 * The stored config is the §7 artifact verbatim EXCEPT the server-owned identity
 * stamp: {@code journeyKey} and {@code version} are rewritten to the path/allocated
 * values so a client cannot publish under a mismatched identity.
 */
public class RegistryService {

    private static final Logger log = LoggerFactory.getLogger(RegistryService.class);

    private final JourneyRegistryStore store;
    private final JourneyConfigValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RegistryService(JourneyRegistryStore store, JourneyConfigValidator validator,
                           ObjectMapper objectMapper, Clock clock) {
        this.store = store;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---- journeys ------------------------------------------------------------

    public JourneyMeta createJourney(String key, String name, String businessLine,
                                     String product, String partner, String actor) {
        requireActor(actor);
        if (key == null || !key.matches("[a-z0-9][a-z0-9-]*")) {
            throw new RegistryException(Kind.VALIDATION_FAILED,
                    "journey key must be lowercase kebab-case ([a-z0-9-]), e.g. 'loan-origination'");
        }
        JourneyMeta created = store.create(JourneyMeta.created(
                key, name == null || name.isBlank() ? key : name, businessLine, product, partner));
        log.info("registry.journey.created key={} actor={}", key, actor);
        return created;
    }

    public JourneyMeta journey(String key) {
        return store.meta(key).orElseThrow(
                () -> new RegistryException(Kind.NOT_FOUND, "no journey '" + key + "'"));
    }

    public List<JourneyMeta> listJourneys() {
        return store.list();
    }

    public List<JourneyVersionRecord> versions(String key) {
        journey(key); // 404 on unknown journey
        return store.versions(key);
    }

    public JourneyVersionRecord version(String key, int version) {
        return store.version(key, version).orElseThrow(() -> new RegistryException(
                Kind.NOT_FOUND, "no version " + version + " of journey '" + key + "'"));
    }

    // ---- maker side ------------------------------------------------------------

    public JourneyVersionRecord createDraft(String key, String configJson, String note, String actor) {
        requireActor(actor);
        journey(key);
        int allocated = store.allocateEditableVersion(key); // atomic single-draft claim
        JourneyVersionRecord draft = new JourneyVersionRecord(
                key, allocated, VersionStatus.DRAFT, actor, null, note,
                stampIdentity(configJson, key, allocated), clock.instant(), clock.instant());
        store.writeVersion(draft);
        log.info("registry.draft.created key={} version={} author={}", key, allocated, actor);
        return draft;
    }

    public JourneyVersionRecord saveDraft(String key, int version, String configJson,
                                          String note, String actor) {
        requireActor(actor);
        JourneyVersionRecord current = version(key, version);
        if (current.status() != VersionStatus.DRAFT) {
            throw new RegistryException(Kind.CONFLICT, "version " + version + " of '" + key
                    + "' is " + current.status() + " — published/rejected/pending versions are immutable");
        }
        JourneyVersionRecord updated = current.withConfig(
                stampIdentity(configJson, key, version), note, clock.instant());
        store.writeVersion(updated);
        return updated;
    }

    public List<ValidationIssue> validate(String key, int version) {
        return validator.validate(parse(version(key, version).configJson()));
    }

    public JourneyVersionRecord submit(String key, int version, String actor) {
        requireActor(actor);
        JourneyVersionRecord current = version(key, version);
        if (current.status() != VersionStatus.DRAFT) {
            throw new RegistryException(Kind.CONFLICT,
                    "only a DRAFT can be submitted (version " + version + " is " + current.status() + ")");
        }
        List<ValidationIssue> issues = validator.validate(parse(current.configJson()));
        if (issues.stream().anyMatch(ValidationIssue::isError)) {
            throw new RegistryException(Kind.VALIDATION_FAILED,
                    "journey '" + key + "' v" + version + " fails validation", issues);
        }
        JourneyVersionRecord submitted =
                current.withStatus(VersionStatus.PENDING_APPROVAL, null, null, clock.instant());
        store.writeVersion(submitted);
        log.info("registry.version.submitted key={} version={} author={}", key, version, current.authorId());
        return submitted;
    }

    // ---- checker side ----------------------------------------------------------

    // Checker actions run in TWO steps: the releaseEditable CAS FIRST (the
    // single-winner election — a concurrent approve+reject cannot both pass),
    // THEN the status write. The reverse order would let the losing checker
    // overwrite the winner's record before discovering it lost. The engine's
    // published reads treat the POINTER as source-of-truth (see publishedConfig)
    // so the crash window between the two steps never mis-serves a config.

    public JourneyVersionRecord approve(String key, int version, String actor) {
        JourneyVersionRecord pending = checkerAction(key, version, actor);
        if (!store.releaseEditable(key, version, version)) {
            throw new RegistryException(Kind.CONFLICT,
                    "version " + version + " of '" + key + "' was already finalized by another checker");
        }
        JourneyVersionRecord published =
                pending.withStatus(VersionStatus.PUBLISHED, actor, null, clock.instant());
        store.writeVersion(published);
        log.info("registry.version.published key={} version={} approver={}", key, version, actor);
        return published;
    }

    public JourneyVersionRecord reject(String key, int version, String comment, String actor) {
        JourneyVersionRecord pending = checkerAction(key, version, actor);
        if (!store.releaseEditable(key, version, null)) {
            throw new RegistryException(Kind.CONFLICT,
                    "version " + version + " of '" + key + "' was already finalized by another checker");
        }
        JourneyVersionRecord rejected =
                pending.withStatus(VersionStatus.REJECTED, actor, comment, clock.instant());
        store.writeVersion(rejected);
        log.info("registry.version.rejected key={} version={} approver={}", key, version, actor);
        return rejected;
    }

    /** Shared checker-side guard: PENDING only, and maker != checker (the 403). */
    private JourneyVersionRecord checkerAction(String key, int version, String actor) {
        requireActor(actor);
        JourneyVersionRecord current = version(key, version);
        if (current.status() != VersionStatus.PENDING_APPROVAL) {
            throw new RegistryException(Kind.CONFLICT, "only a PENDING_APPROVAL version can be"
                    + " approved/rejected (version " + version + " is " + current.status() + ")");
        }
        if (actor.equals(current.authorId())) {
            throw new RegistryException(Kind.FORBIDDEN,
                    "maker-checker: author '" + actor + "' may not approve/reject their own version");
        }
        return current;
    }

    // ---- engine side -------------------------------------------------------------

    /**
     * Every journey's currently-published config (the engine's bootstrap/refresh
     * read). Served THROUGH the meta pointer — the pointer move is the approval's
     * atomic commit point, so a record still mid-write is served regardless of
     * its status field.
     */
    public List<JourneyVersionRecord> publishedConfigs() {
        return store.list().stream()
                .filter(JourneyMeta::hasPublished)
                .map(m -> version(m.key(), m.publishedVersion()))
                .toList();
    }

    /**
     * A specific once-published version (the engine's pinned in-flight fetch —
     * version pinning means HISTORICAL published versions stay fetchable after
     * the pointer moves on). A version is servable when its record says
     * PUBLISHED or the meta pointer currently elects it (crash-window read).
     */
    public JourneyVersionRecord publishedConfig(String key, int version) {
        JourneyVersionRecord record = version(key, version);
        if (record.status() == VersionStatus.PUBLISHED) {
            return record;
        }
        boolean pointerElectsIt = store.meta(key)
                .map(m -> m.publishedVersion() == version)
                .orElse(false);
        if (pointerElectsIt) {
            return record;
        }
        throw new RegistryException(Kind.NOT_FOUND,
                "version " + version + " of '" + key + "' was never published");
    }

    // ---- helpers -------------------------------------------------------------------

    /** Server-owned identity: the stored artifact's journeyKey/version match the registry's. */
    private String stampIdentity(String configJson, String key, int version) {
        ObjectNode root = (ObjectNode) parse(configJson);
        root.put("journeyKey", key);
        root.put("version", version);
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RegistryException(Kind.VALIDATION_FAILED, "config is not serializable JSON",
                    List.of(ValidationIssue.error("emptyDag", "config is not valid JSON", null)));
        }
    }

    private JsonNode parse(String configJson) {
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (node == null || !node.isObject()) {
                throw new RegistryException(Kind.VALIDATION_FAILED, "config must be a JSON object",
                        List.of(ValidationIssue.error("emptyDag", "config must be a JSON object", null)));
            }
            return node;
        } catch (RegistryException e) {
            throw e;
        } catch (Exception e) {
            throw new RegistryException(Kind.VALIDATION_FAILED, "config is not valid JSON",
                    List.of(ValidationIssue.error("emptyDag", "config is not parseable JSON", null)));
        }
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new RegistryException(Kind.UNAUTHENTICATED,
                    "X-User-Id header is required for this operation");
        }
    }
}

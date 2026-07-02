package com.idfcfirstbank.integration.platform.journeyregistry.domain.port;

import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;

import java.util.List;
import java.util.Optional;

/**
 * OUT port: durable journey + version storage. Implementations MUST make the
 * meta-pointer operations atomic under concurrency (generation CAS on Aerospike;
 * per-key synchronization in memory):
 *
 * <ul>
 *   <li>{@link #create} — atomic insert-if-absent of a new journey;</li>
 *   <li>{@link #allocateEditableVersion} — allocates {@code latest+1} AND claims
 *       the single-editable-draft slot in ONE atomic step (the maker-checker
 *       invariant "at most one draft in the pipeline" holds by construction);</li>
 *   <li>{@link #releaseEditable} — clears the editable slot (approve/reject),
 *       optionally moving the published pointer; exactly ONE concurrent caller
 *       wins (the loser sees {@code false} — a duplicate checker action).</li>
 * </ul>
 */
public interface JourneyRegistryStore {

    /** @return the stored meta; throws CONFLICT (RegistryException) if the key exists. */
    JourneyMeta create(JourneyMeta meta);

    Optional<JourneyMeta> meta(String key);

    List<JourneyMeta> list();

    /**
     * Atomically allocate the next version number and claim the editable slot.
     * Throws NOT_FOUND for an unknown journey, CONFLICT when an editable
     * (draft/pending) version already exists.
     */
    int allocateEditableVersion(String key);

    /** Upsert a version record (draft create/save and status transitions persist through this). */
    void writeVersion(JourneyVersionRecord record);

    Optional<JourneyVersionRecord> version(String key, int version);

    /** All versions 1..latest for a journey, ascending. */
    List<JourneyVersionRecord> versions(String key);

    /**
     * Release the editable slot iff it still holds {@code version}; when
     * {@code newPublishedVersion} is non-null also move the published pointer.
     * @return false when another caller already released it (lost race).
     */
    boolean releaseEditable(String key, int version, Integer newPublishedVersion);
}

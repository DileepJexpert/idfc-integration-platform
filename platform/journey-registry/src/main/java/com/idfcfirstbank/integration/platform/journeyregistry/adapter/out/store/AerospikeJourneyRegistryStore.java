package com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException.Kind;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.VersionStatus;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.port.JourneyRegistryStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Durable {@link JourneyRegistryStore} on Aerospike (the org's only datastore).
 * The meta pointer record is the concurrency anchor: {@code allocateEditableVersion}
 * and {@code releaseEditable} are generation-CAS loops (EXPECT_GEN_EQUAL), the
 * same single-winner discipline the engine's instance store uses. Version records
 * are plain upserts — their lifecycle is already serialized by the pointer.
 *
 * <p>Registry records NEVER expire ({@code expiration = -1}): published versions
 * are the audit trail of what the engine ran.
 */
public class AerospikeJourneyRegistryStore implements JourneyRegistryStore {

    private static final String B_KEY = "key";
    private static final String B_NAME = "name";
    private static final String B_BL = "bl";
    private static final String B_PRODUCT = "product";
    private static final String B_PARTNER = "partner";
    private static final String B_LATEST = "latest";
    private static final String B_EDITABLE = "editable";
    private static final String B_PUBLISHED = "published";

    private static final String B_VERSION = "ver";
    private static final String B_STATUS = "status";
    private static final String B_AUTHOR = "author";
    private static final String B_APPROVER = "approver";
    private static final String B_NOTE = "note";
    private static final String B_CONFIG = "config";
    private static final String B_CREATED = "created";
    private static final String B_UPDATED = "updated";

    private static final int NEVER_EXPIRE = -1;
    private static final int HOT_KEY_MAX_RETRIES = 12;
    private static final int CAS_MAX_RETRIES = 64;

    private final IAerospikeClient client;
    private final String namespace;
    private final String metaSet;
    private final String versionSet;

    public AerospikeJourneyRegistryStore(IAerospikeClient client, String namespace,
                                         String metaSet, String versionSet) {
        this.client = client;
        this.namespace = namespace;
        this.metaSet = metaSet;
        this.versionSet = versionSet;
    }

    // ---- meta pointers ----------------------------------------------------------

    @Override
    public JourneyMeta create(JourneyMeta meta) {
        WritePolicy wp = writePolicy();
        wp.recordExistsAction = RecordExistsAction.CREATE_ONLY; // atomic insert-if-absent
        try {
            putWithHotKeyRetry(wp, metaKey(meta.key()), metaBins(meta));
            return meta;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                throw new RegistryException(Kind.CONFLICT, "journey '" + meta.key() + "' already exists");
            }
            throw e;
        }
    }

    @Override
    public Optional<JourneyMeta> meta(String key) {
        Record r = client.get(client.getReadPolicyDefault(), metaKey(key));
        return r == null ? Optional.empty() : Optional.of(metaFrom(r));
    }

    @Override
    public List<JourneyMeta> list() {
        List<JourneyMeta> out = new ArrayList<>();
        ScanPolicy policy = new ScanPolicy(client.getScanPolicyDefault());
        client.scanAll(policy, namespace, metaSet, (scanKey, record) -> {
            if (record != null && record.getString(B_KEY) != null) {
                synchronized (out) {
                    out.add(metaFrom(record));
                }
            }
        });
        out.sort(Comparator.comparing(JourneyMeta::key));
        return out;
    }

    @Override
    public int allocateEditableVersion(String key) {
        for (int attempt = 0; attempt < CAS_MAX_RETRIES; attempt++) {
            Record r = client.get(client.getReadPolicyDefault(), metaKey(key));
            if (r == null) {
                throw new RegistryException(Kind.NOT_FOUND, "no journey '" + key + "'");
            }
            JourneyMeta m = metaFrom(r);
            if (m.hasEditable()) {
                throw new RegistryException(Kind.CONFLICT, "journey '" + key
                        + "' already has an editable version (v" + m.editableVersion() + ")");
            }
            int next = m.latestVersion() + 1;
            JourneyMeta updated = new JourneyMeta(m.key(), m.name(), m.businessLine(), m.product(),
                    m.partner(), next, next, m.publishedVersion());
            if (casPut(r.generation, metaKey(key), metaBins(updated))) {
                return next;
            }
            // generation moved — another allocate/release won this round; re-read.
        }
        throw new RegistryException(Kind.CONFLICT,
                "journey '" + key + "' meta is under heavy contention; retry the draft");
    }

    @Override
    public boolean releaseEditable(String key, int version, Integer newPublishedVersion) {
        for (int attempt = 0; attempt < CAS_MAX_RETRIES; attempt++) {
            Record r = client.get(client.getReadPolicyDefault(), metaKey(key));
            if (r == null) {
                return false;
            }
            JourneyMeta m = metaFrom(r);
            if (m.editableVersion() != version) {
                return false; // another checker already released — lost the race
            }
            JourneyMeta updated = new JourneyMeta(m.key(), m.name(), m.businessLine(), m.product(),
                    m.partner(), m.latestVersion(), 0,
                    newPublishedVersion == null ? m.publishedVersion() : newPublishedVersion);
            if (casPut(r.generation, metaKey(key), metaBins(updated))) {
                return true;
            }
        }
        return false;
    }

    // ---- version records ----------------------------------------------------------

    @Override
    public void writeVersion(JourneyVersionRecord record) {
        putWithHotKeyRetry(writePolicy(), versionKey(record.journeyKey(), record.version()),
                versionBins(record));
    }

    @Override
    public Optional<JourneyVersionRecord> version(String key, int version) {
        Record r = client.get(client.getReadPolicyDefault(), versionKey(key, version));
        return r == null ? Optional.empty() : Optional.of(versionFrom(key, version, r));
    }

    @Override
    public List<JourneyVersionRecord> versions(String key) {
        int latest = meta(key).map(JourneyMeta::latestVersion).orElse(0);
        List<JourneyVersionRecord> out = new ArrayList<>();
        for (int v = 1; v <= latest; v++) {
            version(key, v).ifPresent(out::add);
        }
        return out;
    }

    // ---- plumbing ----------------------------------------------------------------

    /** CAS write at an expected generation; false = the record moved (retry). */
    private boolean casPut(int expectedGeneration, Key key, Bin[] bins) {
        WritePolicy wp = writePolicy();
        wp.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        wp.generation = expectedGeneration;
        try {
            putWithHotKeyRetry(wp, key, bins);
            return true;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.GENERATION_ERROR) {
                return false;
            }
            throw e;
        }
    }

    private void putWithHotKeyRetry(WritePolicy wp, Key key, Bin[] bins) {
        for (int attempt = 0; ; attempt++) {
            try {
                client.put(wp, key, bins);
                return;
            } catch (AerospikeException e) {
                if (e.getResultCode() == ResultCode.KEY_BUSY && attempt < HOT_KEY_MAX_RETRIES) {
                    try {
                        Thread.sleep(Math.min(2L + attempt * 2L, 25L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                    continue;
                }
                throw e;
            }
        }
    }

    private WritePolicy writePolicy() {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = NEVER_EXPIRE;
        wp.sendKey = true;
        return wp;
    }

    private Key metaKey(String key) {
        return new Key(namespace, metaSet, key);
    }

    private Key versionKey(String key, int version) {
        return new Key(namespace, versionSet, key + ":" + version);
    }

    private static Bin[] metaBins(JourneyMeta m) {
        return new Bin[]{
                new Bin(B_KEY, m.key()),
                new Bin(B_NAME, m.name()),
                new Bin(B_BL, m.businessLine()),
                new Bin(B_PRODUCT, m.product()),
                new Bin(B_PARTNER, m.partner()),
                new Bin(B_LATEST, m.latestVersion()),
                new Bin(B_EDITABLE, m.editableVersion()),
                new Bin(B_PUBLISHED, m.publishedVersion()),
        };
    }

    private static JourneyMeta metaFrom(Record r) {
        return new JourneyMeta(
                r.getString(B_KEY), r.getString(B_NAME), r.getString(B_BL),
                r.getString(B_PRODUCT), r.getString(B_PARTNER),
                r.getInt(B_LATEST), r.getInt(B_EDITABLE), r.getInt(B_PUBLISHED));
    }

    private static Bin[] versionBins(JourneyVersionRecord v) {
        return new Bin[]{
                new Bin(B_KEY, v.journeyKey()),
                new Bin(B_VERSION, v.version()),
                new Bin(B_STATUS, v.status().name()),
                new Bin(B_AUTHOR, v.authorId()),
                new Bin(B_APPROVER, v.approverId()),
                new Bin(B_NOTE, v.note()),
                new Bin(B_CONFIG, v.configJson()),
                new Bin(B_CREATED, v.createdAt() == null ? null : v.createdAt().toString()),
                new Bin(B_UPDATED, v.updatedAt() == null ? null : v.updatedAt().toString()),
        };
    }

    private static JourneyVersionRecord versionFrom(String key, int version, Record r) {
        String created = r.getString(B_CREATED);
        String updated = r.getString(B_UPDATED);
        return new JourneyVersionRecord(
                key, version,
                VersionStatus.valueOf(r.getString(B_STATUS)),
                r.getString(B_AUTHOR), r.getString(B_APPROVER), r.getString(B_NOTE),
                r.getString(B_CONFIG),
                created == null ? null : Instant.parse(created),
                updated == null ? null : Instant.parse(updated));
    }
}

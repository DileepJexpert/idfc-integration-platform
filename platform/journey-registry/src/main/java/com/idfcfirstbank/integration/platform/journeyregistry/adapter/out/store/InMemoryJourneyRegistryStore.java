package com.idfcfirstbank.integration.platform.journeyregistry.adapter.out.store;

import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.error.RegistryException.Kind;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyMeta;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.JourneyVersionRecord;
import com.idfcfirstbank.integration.platform.journeyregistry.domain.port.JourneyRegistryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link JourneyRegistryStore} (Docker-free default). The meta-pointer
 * operations are made atomic with the ConcurrentHashMap compute family — the
 * same single-winner semantics the Aerospike generation-CAS gives.
 */
public class InMemoryJourneyRegistryStore implements JourneyRegistryStore {

    private final ConcurrentHashMap<String, JourneyMeta> metaByKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JourneyVersionRecord> versionByKey = new ConcurrentHashMap<>();

    @Override
    public JourneyMeta create(JourneyMeta meta) {
        JourneyMeta existing = metaByKey.putIfAbsent(meta.key(), meta);
        if (existing != null) {
            throw new RegistryException(Kind.CONFLICT, "journey '" + meta.key() + "' already exists");
        }
        return meta;
    }

    @Override
    public Optional<JourneyMeta> meta(String key) {
        return Optional.ofNullable(metaByKey.get(key));
    }

    @Override
    public List<JourneyMeta> list() {
        return metaByKey.values().stream()
                .sorted(Comparator.comparing(JourneyMeta::key))
                .toList();
    }

    @Override
    public int allocateEditableVersion(String key) {
        // The CHM remapping function runs atomically, exactly once — the holder
        // flag distinguishes "this call allocated" from "an editable already
        // existed" with no race (exactly one concurrent caller can allocate).
        boolean[] allocatedByThisCall = {false};
        JourneyMeta updated = metaByKey.computeIfPresent(key, (k, m) -> {
            if (m.hasEditable()) {
                return m;
            }
            allocatedByThisCall[0] = true;
            int next = m.latestVersion() + 1;
            return new JourneyMeta(m.key(), m.name(), m.businessLine(), m.product(), m.partner(),
                    next, next, m.publishedVersion());
        });
        if (updated == null) {
            throw new RegistryException(Kind.NOT_FOUND, "no journey '" + key + "'");
        }
        if (!allocatedByThisCall[0]) {
            throw new RegistryException(Kind.CONFLICT,
                    "journey '" + key + "' already has an editable version (v" + updated.editableVersion() + ")");
        }
        return updated.editableVersion();
    }

    @Override
    public void writeVersion(JourneyVersionRecord record) {
        versionByKey.put(versionKey(record.journeyKey(), record.version()), record);
    }

    @Override
    public Optional<JourneyVersionRecord> version(String key, int version) {
        return Optional.ofNullable(versionByKey.get(versionKey(key, version)));
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

    @Override
    public boolean releaseEditable(String key, int version, Integer newPublishedVersion) {
        boolean[] released = {false};
        metaByKey.computeIfPresent(key, (k, m) -> {
            if (m.editableVersion() != version) {
                return m; // someone else already released — lost the race
            }
            released[0] = true;
            return new JourneyMeta(m.key(), m.name(), m.businessLine(), m.product(), m.partner(),
                    m.latestVersion(), 0,
                    newPublishedVersion == null ? m.publishedVersion() : newPublishedVersion);
        });
        return released[0];
    }

    private static String versionKey(String key, int version) {
        return key + ":" + version;
    }
}

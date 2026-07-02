package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyDecision;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Durable journey-instance store on Aerospike (the org's only datastore) — the
 * engine is the audit source-of-truth for "what ran / current status", so its
 * run state must survive across the async capability hops and engine restarts.
 *
 * <p>Run state is JSON-encoded into bins (the maps/sets round-trip cleanly) and
 * keyed by {@code journeyInstanceId}. A native TTL keeps the demo's instances
 * from accumulating. Selected by config ({@code idfc.engine.state-store=aerospike});
 * the in-memory store remains the Docker-free default.
 */
public class AerospikeJourneyInstanceStore implements JourneyInstanceStore {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final TypeReference<Set<String>> SET = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {};

    private static final String B_ID = "id";
    private static final String B_STARTED = "started";
    private static final String B_PENDING_REQ = "pendReq";
    private static final String B_PENDING_DEC = "pendDec";
    private static final String B_CORR = "corr";
    private static final String B_KEY = "jkey";
    private static final String B_APPREF = "appRef";
    private static final String B_STATUS = "status";
    private static final String B_PAYLOAD = "payload";
    private static final String B_COLLECTED = "collected";
    private static final String B_CONTEXT = "context";
    private static final String B_COMPLETED = "completed";
    private static final String B_DISPATCHED = "dispatched";

    private final IAerospikeClient client;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final String set;
    private final int ttlSeconds;

    public AerospikeJourneyInstanceStore(IAerospikeClient client, ObjectMapper objectMapper,
                                         String namespace, String set, int ttlSeconds) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.set = set;
        this.ttlSeconds = ttlSeconds;
    }

    // Hot-key handling mirrors the edge idempotency store: KEY_BUSY (result code
    // 14) is transient same-key contention, NOT "already exists" — retry briefly.
    private static final int HOT_KEY_MAX_RETRIES = 12;

    @Override
    public boolean insertIfAbsent(JourneyInstance instance) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = expirationFor(instance);
        wp.recordExistsAction = RecordExistsAction.CREATE_ONLY; // atomic insert-if-absent
        for (int attempt = 0; ; attempt++) {
            try {
                client.put(wp, key(instance.journeyInstanceId()), bins(instance));
                instance.version(1); // a freshly created record is generation 1
                return true; // we created it — the single winner
            } catch (AerospikeException e) {
                if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                    return false; // someone already started this journey — duplicate
                }
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

    /**
     * Compare-and-set save: applies ONLY if the record is still at the generation
     * this state was loaded at ({@code EXPECT_GEN_EQUAL}). A concurrent writer —
     * the second engine replica processing another arm of the same journey —
     * makes this throw {@link ConcurrentModificationException}; the caller's
     * Kafka trigger is then redelivered and reprocessed from FRESH state, so no
     * completed-node mark or collected result is ever silently overwritten.
     */
    @Override
    public void save(JourneyInstance instance) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = expirationFor(instance);
        wp.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        wp.generation = (int) instance.version();
        for (int attempt = 0; ; attempt++) {
            try {
                client.put(wp, key(instance.journeyInstanceId()), bins(instance));
                instance.version(instance.version() + 1); // each write bumps the generation by one
                return;
            } catch (AerospikeException e) {
                if (e.getResultCode() == ResultCode.GENERATION_ERROR) {
                    throw new ConcurrentModificationException(
                            "journey instance " + instance.journeyInstanceId()
                                    + " modified concurrently (expected gen " + instance.version() + ")");
                }
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

    /**
     * In-flight (RUNNING) runs NEVER silently expire (-1): a stuck run must survive
     * so the liveness sweeper can find it and so the run record — the audit
     * source-of-truth — outlives the TTL. Only terminal runs keep the configured
     * retention.
     */
    private int expirationFor(JourneyInstance instance) {
        return instance.status() == InstanceStatus.RUNNING ? -1 : ttlSeconds;
    }

    private Bin[] bins(JourneyInstance instance) {
        return new Bin[]{
                new Bin(B_ID, instance.journeyInstanceId()),
                new Bin(B_STARTED, instance.startedAt() == null ? null : instance.startedAt().toString()),
                new Bin(B_CORR, instance.correlationId()),
                new Bin(B_KEY, instance.journeyKey()),
                new Bin(B_APPREF, instance.applicationRef()),
                new Bin(B_STATUS, instance.status().name()),
                new Bin(B_PAYLOAD, json(instance.payload())),
                new Bin(B_COLLECTED, json(instance.collectedResults())),
                new Bin(B_CONTEXT, json(instance.context())),
                new Bin(B_COMPLETED, json(instance.completedNodeIds())),
                new Bin(B_DISPATCHED, json(instance.dispatchedNodeIds())),
                new Bin(B_PENDING_REQ, json(instance.pendingRequestNodeIds())),
                new Bin(B_PENDING_DEC,
                        instance.pendingDecision() == null ? null : json(instance.pendingDecision())),
        };
    }

    @Override
    public Optional<JourneyInstance> find(String journeyInstanceId) {
        Record r = client.get(client.getReadPolicyDefault(), key(journeyInstanceId));
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(restoreFrom(journeyInstanceId, r));
    }

    @Override
    public List<JourneyInstance> findRunningStartedBefore(Instant cutoff) {
        List<JourneyInstance> stuck = new ArrayList<>();
        ScanPolicy policy = new ScanPolicy(client.getScanPolicyDefault());
        client.scanAll(policy, namespace, set, (scanKey, record) -> {
            if (record == null) {
                return;
            }
            String statusName = record.getString(B_STATUS);
            if (statusName == null || InstanceStatus.valueOf(statusName) != InstanceStatus.RUNNING) {
                return;
            }
            String startedStr = record.getString(B_STARTED);
            String id = record.getString(B_ID);
            if (startedStr == null || id == null) {
                return;
            }
            if (Instant.parse(startedStr).isBefore(cutoff)) {
                stuck.add(restoreFrom(id, record));
            }
        });
        return stuck;
    }

    private JourneyInstance restoreFrom(String journeyInstanceId, Record r) {
        String startedStr = r.getString(B_STARTED);
        Instant startedAt = startedStr == null ? null : Instant.parse(startedStr);
        return JourneyInstance.restore(
                journeyInstanceId,
                r.getString(B_CORR), r.getString(B_KEY), r.getString(B_APPREF),
                readMap(r.getString(B_PAYLOAD)), startedAt,
                r.generation, // the version this state was loaded at, for the CAS save
                readMap(r.getString(B_COLLECTED)),
                readMap(r.getString(B_CONTEXT)),
                readSet(r.getString(B_COMPLETED)), readSet(r.getString(B_DISPATCHED)),
                InstanceStatus.valueOf(r.getString(B_STATUS)),
                readList(r.getString(B_PENDING_REQ)), readDecision(r.getString(B_PENDING_DEC)));
    }

    private Key key(String journeyInstanceId) {
        return new Key(namespace, set, journeyInstanceId);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unserializable journey-instance field", e);
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return json == null ? new LinkedHashMap<>() : objectMapper.readValue(json, MAP);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt journey-instance map bin", e);
        }
    }

    private Set<String> readSet(String json) {
        try {
            return json == null ? Set.of() : objectMapper.readValue(json, SET);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt journey-instance set bin", e);
        }
    }

    private List<String> readList(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, LIST);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt journey-instance list bin", e);
        }
    }

    private JourneyDecision readDecision(String json) {
        try {
            return json == null ? null : objectMapper.readValue(json, JourneyDecision.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt journey-instance pending-decision bin", e);
        }
    }
}

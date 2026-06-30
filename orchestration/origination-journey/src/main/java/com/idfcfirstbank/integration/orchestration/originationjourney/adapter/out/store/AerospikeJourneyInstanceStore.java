package com.idfcfirstbank.integration.orchestration.originationjourney.adapter.out.store;

import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.policy.WritePolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.InstanceStatus;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.model.JourneyInstance;
import com.idfcfirstbank.integration.orchestration.originationjourney.domain.port.JourneyInstanceStore;

import java.util.LinkedHashMap;
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

    @Override
    public void save(JourneyInstance instance) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = ttlSeconds;
        client.put(wp, key(instance.journeyInstanceId()),
                new Bin(B_CORR, instance.correlationId()),
                new Bin(B_KEY, instance.journeyKey()),
                new Bin(B_APPREF, instance.applicationRef()),
                new Bin(B_STATUS, instance.status().name()),
                new Bin(B_PAYLOAD, json(instance.payload())),
                new Bin(B_COLLECTED, json(instance.collectedResults())),
                new Bin(B_CONTEXT, json(instance.context())),
                new Bin(B_COMPLETED, json(instance.completedNodeIds())),
                new Bin(B_DISPATCHED, json(instance.dispatchedNodeIds())));
    }

    @Override
    public Optional<JourneyInstance> find(String journeyInstanceId) {
        Record r = client.get(client.getReadPolicyDefault(), key(journeyInstanceId));
        if (r == null) {
            return Optional.empty();
        }
        return Optional.of(JourneyInstance.restore(
                journeyInstanceId,
                r.getString(B_CORR), r.getString(B_KEY), r.getString(B_APPREF),
                readMap(r.getString(B_PAYLOAD)), readMap(r.getString(B_COLLECTED)),
                readMap(r.getString(B_CONTEXT)),
                readSet(r.getString(B_COMPLETED)), readSet(r.getString(B_DISPATCHED)),
                InstanceStatus.valueOf(r.getString(B_STATUS))));
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
}

package com.idfcfirstbank.integration.digitaledge.adapter.out.aerospike;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;

import java.time.Clock;
import java.time.Duration;

/**
 * Statused claim gates on the platform idempotency store (Aerospike). A claim
 * record carries {@code owner}, {@code status} (CLAIMED|PUBLISHED) and a
 * {@code ts} heartbeat; the publish lease decides whether an unpublished claim
 * is a live winner (duplicate) or a crashed attempt (resume/seize). Keys are
 * namespaced with a {@code dig:} prefix so partner request ids can never collide
 * with the SFDC edge's notification records in the shared sets.
 */
public class AerospikeIdempotencyGate implements IdempotencyGatePort {

    private static final String B_OWNER = "owner";
    private static final String B_STATUS = "status";
    private static final String B_TS = "ts";

    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private static final String NOTIFICATION_PREFIX = "dig:";
    private static final String APPLICATION_PREFIX = "dig-app:";

    private static final int HOT_KEY_MAX_RETRIES = 12;

    private final IAerospikeClient client;
    private final String namespace;
    private final String notificationSet;
    private final String applicationSet;
    private final int ttlSeconds;
    private final Clock clock;
    private final Duration publishLease;

    public AerospikeIdempotencyGate(IAerospikeClient client, String namespace, String notificationSet,
                                    String applicationSet, int ttlSeconds, Clock clock, Duration publishLease) {
        this.client = client;
        this.namespace = namespace;
        this.notificationSet = notificationSet;
        this.applicationSet = applicationSet;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
        this.publishLease = publishLease;
    }

    @Override
    public ClaimOutcome claimNotification(String notificationId) {
        Key key = notificationKey(notificationId);
        if (createOnly(key, claimBins(notificationId))) {
            return ClaimOutcome.NEW;
        }
        Record existing = client.get(client.getReadPolicyDefault(), key);
        if (existing == null) {
            // vanished between the failed create and the read (TTL race) — retry create
            return createOnly(key, claimBins(notificationId)) ? ClaimOutcome.NEW : ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        if (STATUS_PUBLISHED.equals(existing.getString(B_STATUS))) {
            return ClaimOutcome.DUPLICATE_PUBLISHED;
        }
        if (!leaseExpired(existing)) {
            return ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        // Crashed attempt: take the claim over (generation CAS — exactly one taker).
        return casTakeover(key, existing, claimBins(notificationId))
                ? ClaimOutcome.RESUME : ClaimOutcome.DUPLICATE_IN_FLIGHT;
    }

    @Override
    public ClaimOutcome claimApplication(String applicationKey, String ownerNotificationId) {
        Key key = applicationKey(applicationKey);
        if (createOnly(key, claimBins(ownerNotificationId))) {
            return ClaimOutcome.NEW;
        }
        Record existing = client.get(client.getReadPolicyDefault(), key);
        if (existing == null) {
            return createOnly(key, claimBins(ownerNotificationId))
                    ? ClaimOutcome.NEW : ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        String owner = existing.getString(B_OWNER);
        if (ownerNotificationId.equals(owner)) {
            return ClaimOutcome.RESUME; // my own earlier attempt
        }
        // Another request id owns this application: duplicate iff the owner's own
        // claim was published or is still live; otherwise the owner crashed — seize.
        Record ownerClaim = owner == null ? null
                : client.get(client.getReadPolicyDefault(), notificationKey(owner));
        if (ownerClaim != null && STATUS_PUBLISHED.equals(ownerClaim.getString(B_STATUS))) {
            return ClaimOutcome.DUPLICATE_PUBLISHED;
        }
        boolean ownerAlive = ownerClaim != null && !leaseExpired(ownerClaim);
        if (ownerAlive) {
            return ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        return casTakeover(key, existing, claimBins(ownerNotificationId))
                ? ClaimOutcome.RESUME : ClaimOutcome.DUPLICATE_IN_FLIGHT;
    }

    @Override
    public void markPublished(String notificationId) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = ttlSeconds;
        client.put(wp, notificationKey(notificationId),
                new Bin(B_OWNER, notificationId),
                new Bin(B_STATUS, STATUS_PUBLISHED),
                new Bin(B_TS, clock.millis()));
    }

    @Override
    public void releaseClaims(String notificationId, String applicationKey) {
        client.delete(client.getWritePolicyDefault(), notificationKey(notificationId));
        // Release the application link only if THIS request id owns it.
        Key appKey = applicationKey(applicationKey);
        Record existing = client.get(client.getReadPolicyDefault(), appKey);
        if (existing != null && notificationId.equals(existing.getString(B_OWNER))) {
            client.delete(client.getWritePolicyDefault(), appKey);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private Key notificationKey(String notificationId) {
        return new Key(namespace, notificationSet, NOTIFICATION_PREFIX + notificationId);
    }

    private Key applicationKey(String applicationKey) {
        return new Key(namespace, applicationSet, APPLICATION_PREFIX + applicationKey);
    }

    private Bin[] claimBins(String owner) {
        return new Bin[]{new Bin(B_OWNER, owner), new Bin(B_STATUS, STATUS_CLAIMED), new Bin(B_TS, clock.millis())};
    }

    private boolean leaseExpired(Record record) {
        Object ts = record.getValue(B_TS);
        if (!(ts instanceof Number heartbeat)) {
            return true; // legacy/heartbeat-less claim — treat as crashed
        }
        return clock.millis() > heartbeat.longValue() + publishLease.toMillis();
    }

    /** Generation-CAS takeover of a stale claim: exactly one concurrent taker wins. */
    private boolean casTakeover(Key key, Record expected, Bin[] bins) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.expiration = ttlSeconds;
        wp.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        wp.generation = expected.generation;
        try {
            client.put(wp, key, bins);
            return true;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.GENERATION_ERROR) {
                return false; // someone else seized it first
            }
            throw e;
        }
    }

    /**
     * CREATE_ONLY claim: true = this caller won (created); false = it already
     * existed (a resend). Under a burst of concurrent writes to the SAME key,
     * Aerospike's hot-key protection can transiently reject with KEY_BUSY (=
     * "retry", not "exists") — retry briefly so exactly-one-winner still holds.
     */
    private boolean createOnly(Key key, Bin[] bins) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        wp.expiration = ttlSeconds;
        AerospikeException lastBusy = null;
        for (int attempt = 0; attempt < HOT_KEY_MAX_RETRIES; attempt++) {
            try {
                client.put(wp, key, bins);
                return true;
            } catch (AerospikeException e) {
                if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                    return false;
                }
                if (e.getResultCode() == ResultCode.KEY_BUSY) {
                    lastBusy = e;
                    try {
                        Thread.sleep(Math.min(2L + attempt * 2L, 25L));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(ie);
                    }
                    continue;
                }
                throw e;
            }
        }
        throw lastBusy;
    }
}

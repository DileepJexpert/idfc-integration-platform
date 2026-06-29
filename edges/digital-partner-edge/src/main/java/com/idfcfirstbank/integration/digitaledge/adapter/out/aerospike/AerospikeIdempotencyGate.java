package com.idfcfirstbank.integration.digitaledge.adapter.out.aerospike;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;

/**
 * The SAME platform idempotency store (Aerospike) the SFDC edge uses, exercised
 * here only as two CREATE_ONLY claim gates. A {@link ResultCode#KEY_EXISTS_ERROR}
 * is the loser of the atomic insert (a resend), not an error.
 */
public class AerospikeIdempotencyGate implements IdempotencyGatePort {

    private static final String B_OWNER = "owner";

    private final IAerospikeClient client;
    private final String namespace;
    private final String notificationSet;
    private final String applicationSet;
    private final int ttlSeconds;

    public AerospikeIdempotencyGate(IAerospikeClient client, String namespace, String notificationSet,
                                    String applicationSet, int ttlSeconds) {
        this.client = client;
        this.namespace = namespace;
        this.notificationSet = notificationSet;
        this.applicationSet = applicationSet;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean claimNotification(String notificationId) {
        return createOnly(new Key(namespace, notificationSet, notificationId), new Bin(B_OWNER, notificationId));
    }

    @Override
    public boolean claimApplication(String applicationKey, String ownerNotificationId) {
        return createOnly(new Key(namespace, applicationSet, applicationKey), new Bin(B_OWNER, ownerNotificationId));
    }

    /**
     * CREATE_ONLY claim: true = this caller won (created); false = it already
     * existed (a resend). Under a burst of concurrent writes to the SAME key,
     * Aerospike's hot-key protection can transiently reject with KEY_BUSY (=
     * "retry", not "exists") — retry briefly so exactly-one-winner still holds.
     */
    private boolean createOnly(Key key, Bin owner) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        wp.expiration = ttlSeconds;
        AerospikeException lastBusy = null;
        for (int attempt = 0; attempt < HOT_KEY_MAX_RETRIES; attempt++) {
            try {
                client.put(wp, key, owner);
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

    private static final int HOT_KEY_MAX_RETRIES = 12;
}

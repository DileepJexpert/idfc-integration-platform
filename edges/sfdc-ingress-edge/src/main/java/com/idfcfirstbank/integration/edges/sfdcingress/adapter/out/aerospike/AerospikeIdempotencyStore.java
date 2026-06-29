package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.aerospike;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.Bin;
import com.aerospike.client.IAerospikeClient;
import com.aerospike.client.Key;
import com.aerospike.client.Record;
import com.aerospike.client.ResultCode;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.ApplicationKey;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RecordStatus;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.CasResult;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * The real Aerospike idempotency store (punch list §D). Encodes the
 * non-negotiable semantics directly on Aerospike primitives:
 *
 * <ul>
 *   <li>{@link #insertIfAbsent} / {@link #linkApplication} use
 *       {@link RecordExistsAction#CREATE_ONLY} — two concurrent identical keys
 *       yield exactly ONE winner (a {@link ResultCode#KEY_EXISTS_ERROR} is the
 *       loser, not an error).</li>
 *   <li>every status change is a compare-and-set on the expected generation
 *       ({@link GenerationPolicy#EXPECT_GEN_EQUAL}); a
 *       {@link ResultCode#GENERATION_ERROR} means someone transitioned first and
 *       is surfaced as a {@link CasResult#stale(IdempotencyRecord)}.</li>
 *   <li>expiry is native TTL ({@code expiration} on create); updates pass
 *       {@code expiration = -2} ("no change") so a transition never shortens the
 *       SFDC-retry-window TTL.</li>
 * </ul>
 *
 * <p>{@link IdempotencyRecord#version()} mirrors the Aerospike record generation;
 * a successful gen-CAS write increments it by one.
 */
public class AerospikeIdempotencyStore implements IdempotencyStorePort {

    private static final int TTL_NO_CHANGE = -2;

    // Bin names (Aerospike bin names are <= 15 chars).
    private static final String B_NOTIF = "notif";
    private static final String B_SFDC = "sfdcRec";
    private static final String B_APPREF = "appRef";
    private static final String B_STATUS = "status";
    private static final String B_ORGID = "orgId";
    private static final String B_ORIG_CORR = "origCorr";
    private static final String B_RECEIVED = "receivedAt";
    private static final String B_UPDATED = "updatedAt";
    private static final String B_RETRY = "retry";
    private static final String B_REDELIV = "redeliv";
    private static final String B_DEC_OUT = "decOut";
    private static final String B_DEC_APP = "decApp";
    private static final String B_DEC_TERMS = "decTerms";
    private static final String B_OWNER = "owner";

    private final IAerospikeClient client;
    private final String namespace;
    private final String recordSet;
    private final String appPointerSet;
    private final int ttlSeconds;
    private final Clock clock;

    public AerospikeIdempotencyStore(IAerospikeClient client, String namespace, String recordSet,
                                     String appPointerSet, int ttlSeconds, Clock clock) {
        this.client = client;
        this.namespace = namespace;
        this.recordSet = recordSet;
        this.appPointerSet = appPointerSet;
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    @Override
    public InsertOutcome insertIfAbsent(IdempotencyRecord record) {
        WritePolicy wp = createOnly();
        try {
            client.put(wp, recordKey(record.notificationId()), recordBins(record));
            return InsertOutcome.INSERTED;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                return InsertOutcome.ALREADY_EXISTS;
            }
            throw e;
        }
    }

    @Override
    public Optional<IdempotencyRecord> findByNotificationId(String notificationId) {
        Record r = client.get(client.getReadPolicyDefault(), recordKey(notificationId));
        return Optional.ofNullable(r).map(rec -> fromRecord(notificationId, rec));
    }

    @Override
    public LinkOutcome linkApplication(ApplicationKey applicationKey, String ownerNotificationId) {
        WritePolicy wp = createOnly();
        try {
            client.put(wp, appKey(applicationKey.value()), new Bin(B_OWNER, ownerNotificationId));
            return LinkOutcome.LINKED;
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.KEY_EXISTS_ERROR) {
                return LinkOutcome.ALREADY_LINKED;
            }
            throw e;
        }
    }

    @Override
    public Optional<String> findOwnerByApplicationKey(ApplicationKey applicationKey) {
        Record r = client.get(client.getReadPolicyDefault(), appKey(applicationKey.value()));
        return Optional.ofNullable(r).map(rec -> rec.getString(B_OWNER));
    }

    @Override
    public CasResult compareAndSetStatus(IdempotencyRecord expected, RecordStatus next, Decision decisionOrNull) {
        Instant now = clock.instant();
        IdempotencyRecord target = decisionOrNull != null
                ? expected.withDecision(decisionOrNull, now)
                : expected.withStatus(next, now);
        return casWrite(expected, target);
    }

    @Override
    public CasResult compareAndIncrementRetry(IdempotencyRecord expected) {
        return casWrite(expected, expected.withRetryCount(expected.retryCount() + 1, clock.instant()));
    }

    @Override
    public CasResult compareAndIncrementRedelivery(IdempotencyRecord expected) {
        return casWrite(expected, expected.withRedeliveryCount(expected.redeliveryCount() + 1, clock.instant()));
    }

    /** Gen-CAS write of {@code target} expecting {@code expected.version()} as the generation. */
    private CasResult casWrite(IdempotencyRecord expected, IdempotencyRecord target) {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.recordExistsAction = RecordExistsAction.UPDATE;
        wp.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
        wp.generation = (int) expected.version();
        wp.expiration = TTL_NO_CHANGE; // never shorten the SFDC-retry-window TTL on a transition
        try {
            client.put(wp, recordKey(expected.notificationId()), recordBins(target));
            // A successful gen-CAS write bumps the Aerospike generation by one.
            return CasResult.applied(target.withVersion(expected.version() + 1));
        } catch (AerospikeException e) {
            if (e.getResultCode() == ResultCode.GENERATION_ERROR) {
                return CasResult.stale(findByNotificationId(expected.notificationId()).orElse(null));
            }
            throw e;
        }
    }

    // --- mapping -----------------------------------------------------------------

    private WritePolicy createOnly() {
        WritePolicy wp = new WritePolicy(client.getWritePolicyDefault());
        wp.recordExistsAction = RecordExistsAction.CREATE_ONLY;
        wp.expiration = ttlSeconds;
        return wp;
    }

    private Key recordKey(String notificationId) {
        return new Key(namespace, recordSet, notificationId);
    }

    private Key appKey(String applicationKeyValue) {
        return new Key(namespace, appPointerSet, applicationKeyValue);
    }

    private Bin[] recordBins(IdempotencyRecord r) {
        Bin[] base = {
                new Bin(B_NOTIF, r.notificationId()),
                new Bin(B_SFDC, r.sfdcRecordId()),
                new Bin(B_APPREF, r.applicationRef()),
                new Bin(B_STATUS, r.status().name()),
                new Bin(B_ORGID, r.orgId()),
                new Bin(B_ORIG_CORR, r.originalCorrelationId()),
                new Bin(B_RECEIVED, epochMilli(r.receivedAt())),
                new Bin(B_UPDATED, epochMilli(r.updatedAt())),
                new Bin(B_RETRY, r.retryCount()),
                new Bin(B_REDELIV, r.redeliveryCount()),
        };
        Decision d = r.decision();
        if (d == null) {
            return base;
        }
        Bin[] all = new Bin[base.length + 3];
        System.arraycopy(base, 0, all, 0, base.length);
        all[base.length] = new Bin(B_DEC_OUT, d.outcome());
        all[base.length + 1] = new Bin(B_DEC_APP, d.applicationId());
        all[base.length + 2] = new Bin(B_DEC_TERMS, d.termsJson());
        return all;
    }

    private IdempotencyRecord fromRecord(String notificationId, Record rec) {
        Decision decision = rec.getString(B_DEC_OUT) == null
                ? null
                : new Decision(rec.getString(B_DEC_OUT), rec.getString(B_DEC_APP), rec.getString(B_DEC_TERMS));
        return new IdempotencyRecord(
                notificationId,
                rec.getString(B_SFDC),
                rec.getString(B_APPREF),
                RecordStatus.valueOf(rec.getString(B_STATUS)),
                decision,
                rec.getString(B_ORIG_CORR),
                rec.getString(B_ORGID),
                instant(rec.getLong(B_RECEIVED)),
                instant(rec.getLong(B_UPDATED)),
                (int) rec.getLong(B_RETRY),
                (int) rec.getLong(B_REDELIV),
                rec.generation);
    }

    private static long epochMilli(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static Instant instant(long epochMilli) {
        return epochMilli == 0L ? null : Instant.ofEpochMilli(epochMilli);
    }
}

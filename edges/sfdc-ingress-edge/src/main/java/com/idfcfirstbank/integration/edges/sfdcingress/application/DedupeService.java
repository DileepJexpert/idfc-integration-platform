package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.ApplicationKey;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RecordStatus;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort.InsertOutcome;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort.LinkOutcome;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves the dedupe verdict for an inbound event (the 4 paths + composite-key
 * resolution). NO side effects beyond the atomic store operations needed to claim
 * the winner; publishing/transitions are the orchestrator's job.
 *
 * <p>Composite key (punch list §A/§B): the PRIMARY gate is the CREATE_ONLY insert
 * keyed by {@code notificationId} (this is what the concurrency test hammers).
 * The fallback {@code sfdcRecordId+applicationRef} is enforced via an
 * application-pointer record: a resend that arrives with a NEW notificationId but
 * the SAME application resolves to the existing owner and must NOT double-book.
 * {@code correlationId} is NEVER consulted here.
 */
public class DedupeService {

    /** Default publish lease: an IN_FLIGHT older than this is a crashed attempt. */
    public static final Duration DEFAULT_PUBLISH_LEASE = Duration.ofSeconds(60);

    private final IdempotencyStorePort store;
    private final Clock clock;
    private final Duration publishLease;

    public DedupeService(IdempotencyStorePort store, Clock clock) {
        this(store, clock, DEFAULT_PUBLISH_LEASE);
    }

    public DedupeService(IdempotencyStorePort store, Clock clock, Duration publishLease) {
        this.store = store;
        this.clock = clock;
        this.publishLease = publishLease;
    }

    public DedupeResult resolve(SfdcInboundEvent event) {
        Optional<IdempotencyRecord> primary = store.findByNotificationId(event.notificationId());
        if (primary.isPresent()) {
            return resolveExisting(event, primary.get());
        }

        // Primary absent: contend for the win. The notificationId CREATE_ONLY is
        // the atomic gate — exactly one concurrent identical request wins.
        IdempotencyRecord candidate = IdempotencyRecord.newReceived(
                event.notificationId(), event.sfdcRecordId(), event.applicationRef(),
                event.orgId(), event.correlationId(), clock.instant());

        InsertOutcome insert = store.insertIfAbsent(candidate);
        if (insert == InsertOutcome.ALREADY_EXISTS) {
            // Lost the identical-id race; the winner just created it. A freshly
            // created record is by definition within its lease — re-attach as a
            // live in-flight duplicate, never as stalled.
            IdempotencyRecord current = store.findByNotificationId(event.notificationId())
                    .orElse(candidate.withStatus(RecordStatus.IN_FLIGHT, clock.instant()));
            return resolveExisting(event, current);
        }

        // We won the notificationId. Now guard the business application so a
        // DIFFERENT notificationId for the SAME application cannot double-book.
        IdempotencyRecord winner = store.findByNotificationId(event.notificationId()).orElse(candidate);
        if (event.hasApplicationFallback()) {
            ApplicationKey appKey = ApplicationKey.of(event.sfdcRecordId(), event.applicationRef());
            LinkOutcome link = store.linkApplication(appKey, event.notificationId());
            if (link == LinkOutcome.ALREADY_LINKED) {
                // Another notificationId already owns this application. Our just-
                // created primary becomes a harmless orphan (shadowed by the
                // ownership check below on any future arrival) and will TTL out.
                return resolveAgainstOwner(appKey);
            }
        }
        return DedupeResult.winner(winner);
    }

    /** Branch an existing-record arrival, enforcing application ownership first. */
    private DedupeResult resolveExisting(SfdcInboundEvent event, IdempotencyRecord record) {
        if (event.hasApplicationFallback()) {
            ApplicationKey appKey = ApplicationKey.of(event.sfdcRecordId(), event.applicationRef());
            Optional<String> owner = store.findOwnerByApplicationKey(appKey);
            if (owner.isPresent() && !owner.get().equals(event.notificationId())) {
                // This notificationId is NOT the owner of its application — always
                // resolve against the owner; never publish or re-enqueue.
                return resolveAgainstOwner(appKey, owner.get());
            }
        }
        return DedupeResult.resend(pathFor(record), record, false);
    }

    private DedupeResult resolveAgainstOwner(ApplicationKey appKey) {
        return store.findOwnerByApplicationKey(appKey)
                .map(owner -> resolveAgainstOwner(appKey, owner))
                .orElseThrow(() -> new IllegalStateException("application link vanished: " + appKey.value()));
    }

    private DedupeResult resolveAgainstOwner(ApplicationKey appKey, String ownerNotificationId) {
        IdempotencyRecord owner = store.findByNotificationId(ownerNotificationId)
                // Owner record not yet visible (rare race): treat as in-flight.
                .orElse(IdempotencyRecord.newReceived(ownerNotificationId, null, null, null, null, clock.instant())
                        .withStatus(RecordStatus.IN_FLIGHT, clock.instant()));
        return DedupeResult.resend(pathFor(owner), owner, true);
    }

    /**
     * Map a record's status to the resend path. A pre-publish state (RECEIVED /
     * IN_FLIGHT) WITHIN the publish lease is a live duplicate — the winner is
     * still working; ACK and do not publish. PAST the lease the original attempt
     * is dead (crashed between claim and publish) and ACKing would silently lose
     * the message forever — re-drive instead.
     */
    private DedupePath pathFor(IdempotencyRecord record) {
        return switch (record.status()) {
            case RECEIVED, IN_FLIGHT -> leaseExpired(record) ? DedupePath.STALLED : DedupePath.IN_FLIGHT;
            case PUBLISHED -> DedupePath.PUBLISHED;
            case DECIDED -> DedupePath.DECIDED;
            case FAILED -> DedupePath.FAILED;
        };
    }

    private boolean leaseExpired(IdempotencyRecord record) {
        Instant updatedAt = record.updatedAt();
        if (updatedAt == null) {
            return true; // no heartbeat at all — treat as crashed
        }
        return clock.instant().isAfter(updatedAt.plus(publishLease));
    }
}

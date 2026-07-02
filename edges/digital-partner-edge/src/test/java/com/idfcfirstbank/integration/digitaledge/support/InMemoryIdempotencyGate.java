package com.idfcfirstbank.integration.digitaledge.support;

import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory statused claim gate mirroring the Aerospike adapter's semantics:
 * CLAIMED→PUBLISHED status, publish lease with resume/seize on crashed attempts,
 * and release-on-synchronous-failure.
 */
public class InMemoryIdempotencyGate implements IdempotencyGatePort {

    private record Claim(String owner, boolean published, long claimedAtMillis) {
    }

    private final ConcurrentHashMap<String, Claim> notifications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Claim> applications = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration publishLease;

    public InMemoryIdempotencyGate() {
        this(Clock.systemUTC(), Duration.ofSeconds(60));
    }

    public InMemoryIdempotencyGate(Clock clock, Duration publishLease) {
        this.clock = clock;
        this.publishLease = publishLease;
    }

    @Override
    public ClaimOutcome claimNotification(String notificationId) {
        Claim fresh = new Claim(notificationId, false, clock.millis());
        Claim existing = notifications.putIfAbsent(notificationId, fresh);
        if (existing == null) {
            return ClaimOutcome.NEW;
        }
        if (existing.published()) {
            return ClaimOutcome.DUPLICATE_PUBLISHED;
        }
        if (!leaseExpired(existing)) {
            return ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        // atomic takeover of the crashed attempt
        return notifications.replace(notificationId, existing, fresh)
                ? ClaimOutcome.RESUME : ClaimOutcome.DUPLICATE_IN_FLIGHT;
    }

    @Override
    public ClaimOutcome claimApplication(String applicationKey, String ownerNotificationId) {
        Claim fresh = new Claim(ownerNotificationId, false, clock.millis());
        Claim existing = applications.putIfAbsent(applicationKey, fresh);
        if (existing == null) {
            return ClaimOutcome.NEW;
        }
        if (ownerNotificationId.equals(existing.owner())) {
            return ClaimOutcome.RESUME;
        }
        Claim ownerClaim = notifications.get(existing.owner());
        if (ownerClaim != null && ownerClaim.published()) {
            return ClaimOutcome.DUPLICATE_PUBLISHED;
        }
        boolean ownerAlive = ownerClaim != null && !leaseExpired(ownerClaim);
        if (ownerAlive) {
            return ClaimOutcome.DUPLICATE_IN_FLIGHT;
        }
        return applications.replace(applicationKey, existing, fresh)
                ? ClaimOutcome.RESUME : ClaimOutcome.DUPLICATE_IN_FLIGHT;
    }

    @Override
    public void markPublished(String notificationId) {
        notifications.computeIfPresent(notificationId,
                (id, claim) -> new Claim(claim.owner(), true, claim.claimedAtMillis()));
    }

    @Override
    public void releaseClaims(String notificationId, String applicationKey) {
        notifications.remove(notificationId);
        applications.computeIfPresent(applicationKey,
                (key, claim) -> notificationId.equals(claim.owner()) ? null : claim);
    }

    private boolean leaseExpired(Claim claim) {
        return clock.millis() > claim.claimedAtMillis() + publishLease.toMillis();
    }
}

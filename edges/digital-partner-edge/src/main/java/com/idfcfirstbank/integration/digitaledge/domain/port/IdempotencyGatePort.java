package com.idfcfirstbank.integration.digitaledge.domain.port;

/**
 * OUT port for the SAME platform idempotency store the SFDC edge uses (Aerospike).
 * Two claim gates prevent a double-book on partner resends (at-least-once), but a
 * claim is a STATUS MACHINE, not a one-shot bit: {@code CLAIMED -> PUBLISHED}.
 * Only a claim whose publish was CONFIRMED makes a resend a true duplicate —
 * otherwise the resend must be able to re-drive the publish, or the message is
 * silently lost while every retry gets a false duplicate-ACK.
 *
 * <ul>
 *   <li>{@link #claimNotification} — the primary key (partner request id);</li>
 *   <li>{@link #claimApplication} — the composite fallback (partner+applicationRef),
 *       catching a resend with a NEW request id for the SAME application. A stale
 *       unpublished owner (crashed attempt past the publish lease) is SEIZED;</li>
 *   <li>{@link #markPublished} — records the confirmed publish;</li>
 *   <li>{@link #releaseClaims} — a SYNCHRONOUS publish failure (the partner gets a
 *       503 and will retry) releases this attempt's claims so the retry is clean.
 *       A crash (no 503 path) is covered by the lease instead.</li>
 * </ul>
 */
public interface IdempotencyGatePort {

    enum ClaimOutcome {
        /** First claim — proceed to publish. */
        NEW,
        /** A prior unpublished claim expired its lease (crashed attempt) — proceed and re-drive. */
        RESUME,
        /** A live unpublished claim within its lease — the winner is still working; duplicate. */
        DUPLICATE_IN_FLIGHT,
        /** The claim's publish was confirmed — a true idempotent duplicate. */
        DUPLICATE_PUBLISHED;

        public boolean mayProceed() {
            return this == NEW || this == RESUME;
        }
    }

    ClaimOutcome claimNotification(String notificationId);

    ClaimOutcome claimApplication(String applicationKey, String ownerNotificationId);

    /** Record the confirmed publish for {@code notificationId}'s claim. */
    void markPublished(String notificationId);

    /** Publish failed synchronously: release THIS attempt's claims so the partner's retry is NEW. */
    void releaseClaims(String notificationId, String applicationKey);
}

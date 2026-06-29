package com.idfcfirstbank.integration.digitaledge.domain.port;

/**
 * OUT port for the SAME platform idempotency store the SFDC edge uses (Aerospike
 * CREATE_ONLY). Two atomic claim gates prevent a double-book on partner resends
 * (which are at-least-once too):
 *
 * <ul>
 *   <li>{@link #claimNotification} — the primary key (partner request id). The
 *       single winner of the CREATE_ONLY proceeds; a loser is an exact resend.</li>
 *   <li>{@link #claimApplication} — the composite fallback key
 *       (partner + applicationRef), catching a resend that arrives with a NEW
 *       request id but the SAME business application.</li>
 * </ul>
 *
 * <p>Mirrors the SFDC edge's dedupe essence; the full record state-machine lives
 * in the SFDC edge today and is a future extraction to {@code platform-idempotency}.
 */
public interface IdempotencyGatePort {

    /** @return true if this caller is the single winner for {@code notificationId}. */
    boolean claimNotification(String notificationId);

    /** @return true if this caller is the first owner of {@code applicationKey}. */
    boolean claimApplication(String applicationKey, String ownerNotificationId);
}

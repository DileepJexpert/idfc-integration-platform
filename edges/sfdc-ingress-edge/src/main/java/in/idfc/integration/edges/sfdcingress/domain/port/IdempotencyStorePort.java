package in.idfc.integration.edges.sfdcingress.domain.port;

import in.idfc.integration.edges.sfdcingress.domain.model.ApplicationKey;
import in.idfc.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import in.idfc.integration.edges.sfdcingress.domain.model.RecordStatus;
import in.idfc.integration.edges.sfdcingress.domain.model.Decision;

import java.util.Optional;

/**
 * OUT port for the atomic idempotency store (punch list §D). The ONLY way any
 * caller touches persistence — the concrete Aerospike adapter is never imported
 * directly, so the later extraction to {@code platform-idempotency} is a move,
 * not a rewrite.
 *
 * <p>Non-negotiable semantics:
 * <ul>
 *   <li>{@link #insertIfAbsent} is atomic insert-if-absent (Aerospike
 *       CREATE_ONLY) — two concurrent identical keys yield exactly ONE winner.</li>
 *   <li>Every status change is compare-and-set on the expected generation
 *       (Aerospike EXPECT_GEN_EQUAL) — never read-then-write.</li>
 *   <li>Expiry is native store TTL ({@code >=} SFDC retry window); no purge job.</li>
 * </ul>
 */
public interface IdempotencyStorePort {

    /** Result of the atomic primary insert keyed by notificationId. */
    enum InsertOutcome { INSERTED, ALREADY_EXISTS }

    /** Result of the atomic application-pointer insert (composite fallback gate). */
    enum LinkOutcome { LINKED, ALREADY_LINKED }

    /**
     * Atomically insert the canonical record keyed by {@code notificationId}.
     * INSERTED = this caller is the single winner; ALREADY_EXISTS = a concurrent
     * or prior request already created it.
     */
    InsertOutcome insertIfAbsent(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByNotificationId(String notificationId);

    /**
     * Atomically link the composite-fallback application key to its owning
     * notificationId (the first event that booked this application). The winner
     * of this CREATE_ONLY is the single owner of the business application.
     */
    LinkOutcome linkApplication(ApplicationKey applicationKey, String ownerNotificationId);

    /** Resolve the owning notificationId for a business application, if linked. */
    Optional<String> findOwnerByApplicationKey(ApplicationKey applicationKey);

    /**
     * Compare-and-set the status (and optionally the decision) on the expected
     * generation carried by {@code expected.version()}. Atomic; never
     * read-then-write.
     */
    CasResult compareAndSetStatus(IdempotencyRecord expected, RecordStatus next, Decision decisionOrNull);

    /** CAS-increment retryCount (C3 ceiling). Returns the resulting record. */
    CasResult compareAndIncrementRetry(IdempotencyRecord expected);

    /** CAS-increment redeliveryCount (C5 edge poison breaker). Returns the resulting record. */
    CasResult compareAndIncrementRedelivery(IdempotencyRecord expected);
}

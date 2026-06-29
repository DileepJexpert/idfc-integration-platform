package com.idfcfirstbank.integration.edges.sfdcingress.support;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.ApplicationKey;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RecordStatus;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.CasResult;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast in-memory fake mirroring the Aerospike store's atomic semantics, for unit
 * tests of dedupe/orchestration paths. It deliberately models CREATE_ONLY
 * (putIfAbsent) and generation CAS so behavioural tests are faithful — but it is
 * NOT a substitute for the real-Aerospike concurrency gate (an in-memory map
 * would pass the atomicity test falsely; punch list §D).
 */
public class InMemoryIdempotencyStore implements IdempotencyStorePort {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();
    private final Map<String, String> appPointers = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized InsertOutcome insertIfAbsent(IdempotencyRecord record) {
        IdempotencyRecord prev = records.putIfAbsent(record.notificationId(), record.withVersion(1));
        return prev == null ? InsertOutcome.INSERTED : InsertOutcome.ALREADY_EXISTS;
    }

    @Override
    public Optional<IdempotencyRecord> findByNotificationId(String notificationId) {
        return Optional.ofNullable(records.get(notificationId));
    }

    @Override
    public synchronized LinkOutcome linkApplication(ApplicationKey applicationKey, String ownerNotificationId) {
        String prev = appPointers.putIfAbsent(applicationKey.value(), ownerNotificationId);
        return prev == null ? LinkOutcome.LINKED : LinkOutcome.ALREADY_LINKED;
    }

    @Override
    public Optional<String> findOwnerByApplicationKey(ApplicationKey applicationKey) {
        return Optional.ofNullable(appPointers.get(applicationKey.value()));
    }

    @Override
    public synchronized CasResult compareAndSetStatus(IdempotencyRecord expected, RecordStatus next, Decision dec) {
        IdempotencyRecord current = records.get(expected.notificationId());
        if (current == null || current.version() != expected.version()) {
            return CasResult.stale(current);
        }
        IdempotencyRecord updated = (dec != null
                ? current.withDecision(dec, clock.instant())
                : current.withStatus(next, clock.instant())).withVersion(current.version() + 1);
        records.put(expected.notificationId(), updated);
        return CasResult.applied(updated);
    }

    @Override
    public synchronized CasResult compareAndIncrementRetry(IdempotencyRecord expected) {
        IdempotencyRecord current = records.get(expected.notificationId());
        IdempotencyRecord updated = current.withRetryCount(current.retryCount() + 1, clock.instant())
                .withVersion(current.version() + 1);
        records.put(expected.notificationId(), updated);
        return CasResult.applied(updated);
    }

    @Override
    public synchronized CasResult compareAndIncrementRedelivery(IdempotencyRecord expected) {
        IdempotencyRecord current = records.get(expected.notificationId());
        IdempotencyRecord updated = current.withRedeliveryCount(current.redeliveryCount() + 1, clock.instant())
                .withVersion(current.version() + 1);
        records.put(expected.notificationId(), updated);
        return CasResult.applied(updated);
    }
}

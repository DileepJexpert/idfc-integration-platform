package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.exception.ConfigNotFoundException;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.exception.EdgeException;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.exception.TransientEdgeException;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.CanonicalEnvelope;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.IdempotencyRecord;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RecordStatus;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.RoutingDecision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SourceSystem;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.BlobStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.CasResult;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.IdempotencyStorePort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.OrgConfigPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The thin edge orchestration: dedupe (idempotency first) → for a new winner,
 * normalize → route → claim-check → publish → fast-ACK. NO business logic.
 *
 * <p>Error handling encodes the punch-list tightenings:
 * <ul>
 *   <li><b>C2</b> — ACK + DLQ only on provably-permanent errors; transient
 *       errors do NOT ACK (SFDC redelivers); unclassified defaults to transient.</li>
 *   <li><b>C2 unknown-org/type</b> — refresh config + re-check once before
 *       classifying as permanent.</li>
 *   <li><b>C4</b> — every status change is a CAS on the expected prior state.</li>
 *   <li><b>C5</b> — bound redeliveries per dedup key; on the Nth that never
 *       reaches a clean publish, reclassify transient → poison (ACK + DLQ + alert).</li>
 * </ul>
 */
public class SfdcIngressService {

    private static final Logger log = LoggerFactory.getLogger(SfdcIngressService.class);

    private final DedupeService dedupeService;
    private final IdempotencyStorePort store;
    private final OrgConfigPort orgConfig;
    private final BlobStorePort blobStore;
    private final MessagePublisherPort publisher;
    private final Normalizer normalizer;
    private final EdgePolicies policies;
    private final Clock clock;

    public SfdcIngressService(DedupeService dedupeService, IdempotencyStorePort store,
                              OrgConfigPort orgConfig, BlobStorePort blobStore,
                              MessagePublisherPort publisher, Normalizer normalizer,
                              EdgePolicies policies, Clock clock) {
        this.dedupeService = dedupeService;
        this.store = store;
        this.orgConfig = orgConfig;
        this.blobStore = blobStore;
        this.publisher = publisher;
        this.normalizer = normalizer;
        this.policies = policies;
        this.clock = clock;
    }

    public EdgeResult ingest(SfdcInboundEvent event) {
        DedupeResult verdict = dedupeService.resolve(event);
        if (verdict.resend()) {
            logResend(event, verdict);
        }

        return switch (verdict.path()) {
            case NEW -> processForPublish(event, verdict.record());
            case IN_FLIGHT -> new EdgeResult(EdgeDisposition.ACK_DUPLICATE_INFLIGHT,
                    event.notificationId(), "resend re-attached to in-flight record; no publish");
            case DECIDED -> new EdgeResult(EdgeDisposition.ACK_DUPLICATE_DECIDED,
                    event.notificationId(), "resend after decision; idempotent, no push (C1)");
            case FAILED -> handleFailedRedelivery(event, verdict.record());
        };
    }

    /**
     * Drive a record from RECEIVED/FAILED to a clean publish. Permanent errors →
     * ACK + DLQ (C2). Transient errors → C5 accounting: redelivery bump, and at
     * the poison threshold ACK + DLQ-as-poison; otherwise do NOT ACK.
     */
    private EdgeResult processForPublish(SfdcInboundEvent event, IdempotencyRecord record) {
        try {
            RoutingDecision routing = resolveRoutingWithRecheck(event);
            ensureKnownOrgWithRecheck(event);

            IdempotencyRecord inFlight = transitionToInFlight(record);

            String payloadRef = claimCheck(event);
            CanonicalEnvelope envelope =
                    normalizer.toEnvelope(event, routing, payloadRef, inFlight.originalCorrelationId());
            publishOrThrow(envelope, routing, headersFor(event, inFlight));

            log.info("edge.published notificationId={} type={} topic={} txId={}",
                    event.notificationId(), routing.typeCode(), routing.topic(), envelope.transactionId());
            return new EdgeResult(EdgeDisposition.ACK_PROCESSED, event.notificationId(),
                    "normalized and published to " + routing.topic());

        } catch (EdgeException e) {
            if (e.isPermanent()) {
                return toDlqPermanent(event, record, e);
            }
            return onTransientFailure(event, record, e);
        }
    }

    /** C5: a redelivery of a previously-failed key re-attempts the publish. */
    private EdgeResult handleFailedRedelivery(SfdcInboundEvent event, IdempotencyRecord record) {
        log.warn("edge.failed-resend notificationId={} redeliveryCount={} retryCount={}",
                event.notificationId(), record.redeliveryCount(), record.retryCount());
        return processForPublish(event, record);
    }

    private EdgeResult onTransientFailure(SfdcInboundEvent event, IdempotencyRecord record, EdgeException cause) {
        // Persist the failure and bump the C5 edge-redelivery counter (CAS-safe).
        IdempotencyRecord failed = casToFailed(record.notificationId());
        CasResult bumped = store.compareAndIncrementRedelivery(failed);
        int redeliveries = bumped.record().redeliveryCount();

        if (redeliveries >= policies.poisonRedeliveryThreshold()) {
            // C5 breaker: each attempt looked transient, but the loop is bounded.
            publisher.publishToDlq(poisonEnvelope(event), headersFor(event, bumped.record()),
                    "C5 poison: " + redeliveries + " redeliveries without clean publish; " + cause.getMessage());
            log.error("edge.poison notificationId={} redeliveryCount={} reason={} ALERT",
                    event.notificationId(), redeliveries, cause.getMessage());
            return new EdgeResult(EdgeDisposition.ACK_DLQ_POISON, event.notificationId(),
                    "C5 poison breaker tripped at " + redeliveries + " redeliveries");
        }

        log.warn("edge.transient notificationId={} redeliveryCount={} (no ACK; SFDC will redeliver) cause={}",
                event.notificationId(), redeliveries, cause.getMessage());
        return new EdgeResult(EdgeDisposition.RETRY_TRANSIENT, event.notificationId(),
                "transient failure; not acknowledged so SFDC redelivers");
    }

    private EdgeResult toDlqPermanent(SfdcInboundEvent event, IdempotencyRecord record, EdgeException cause) {
        casToFailed(record.notificationId());
        publisher.publishToDlq(poisonEnvelope(event), headersFor(event, record),
                "C2 permanent: " + cause.getMessage());
        log.error("edge.dlq-permanent notificationId={} reason={} ALERT", event.notificationId(), cause.getMessage());
        return new EdgeResult(EdgeDisposition.ACK_DLQ_PERMANENT, event.notificationId(),
                "permanent error parked in DLQ: " + cause.getMessage());
    }

    // --- routing / org with C2 refresh-and-recheck -------------------------------

    private RoutingDecision resolveRoutingWithRecheck(SfdcInboundEvent event) {
        Optional<RoutingDecision> routing = orgConfig.resolveRouting(SourceSystem.SFDC, event.typeCode());
        if (routing.isEmpty()) {
            orgConfig.refresh(); // unknown type may be stale config — refresh and re-check ONCE
            routing = orgConfig.resolveRouting(SourceSystem.SFDC, event.typeCode());
        }
        return routing.orElseThrow(() -> new ConfigNotFoundException(
                "no routing for (SFDC, type=" + event.typeCode() + ") after refresh"));
    }

    private void ensureKnownOrgWithRecheck(SfdcInboundEvent event) {
        if (!orgConfig.isKnownOrg(event.orgId())) {
            orgConfig.refresh(); // unknown-org vs stale-config look identical — refresh, re-check ONCE
            if (!orgConfig.isKnownOrg(event.orgId())) {
                throw new ConfigNotFoundException("unknown org after refresh: " + event.orgId());
            }
        }
    }

    // --- CAS transitions (C4) ----------------------------------------------------

    private IdempotencyRecord transitionToInFlight(IdempotencyRecord record) {
        if (record.status() == RecordStatus.IN_FLIGHT) {
            return record;
        }
        CasResult cas = store.compareAndSetStatus(record, RecordStatus.IN_FLIGHT, null);
        if (!cas.applied()) {
            // Someone transitioned first; proceed against the current state.
            return cas.record();
        }
        return cas.record();
    }

    /** Drive the record to FAILED from whatever its CURRENT state is (CAS-retry loop). */
    private IdempotencyRecord casToFailed(String notificationId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            IdempotencyRecord current = store.findByNotificationId(notificationId).orElse(null);
            if (current == null) {
                throw new TransientEdgeException("record vanished while marking FAILED: " + notificationId);
            }
            if (current.status() == RecordStatus.FAILED || current.status() == RecordStatus.DECIDED) {
                return current; // terminal-ish; nothing to do
            }
            CasResult cas = store.compareAndSetStatus(current, RecordStatus.FAILED, null);
            if (cas.applied()) {
                return cas.record();
            }
            // generation moved under us — re-read and retry
        }
        return store.findByNotificationId(notificationId).orElseThrow();
    }

    // --- side effects, wrapped so failures classify as transient -----------------

    private String claimCheck(SfdcInboundEvent event) {
        try {
            return blobStore.put(event.rawPayload(), event.payloadContentType());
        } catch (RuntimeException e) {
            throw new TransientEdgeException("claim-check store unavailable", e);
        }
    }

    private void publishOrThrow(CanonicalEnvelope envelope, RoutingDecision routing, Map<String, String> headers) {
        try {
            publisher.publish(envelope, routing, headers);
        } catch (RuntimeException e) {
            throw new TransientEdgeException("kafka publish failed", e);
        }
    }

    private Map<String, String> headersFor(SfdcInboundEvent event, IdempotencyRecord record) {
        Map<String, String> headers = new HashMap<>();
        headers.put("correlationId", event.correlationId());          // trace only
        headers.put("originalCorrelationId", record.originalCorrelationId());
        headers.put("notificationId", event.notificationId());
        headers.put("orgId", event.orgId());
        if (!event.correlationId().equals(record.originalCorrelationId())) {
            headers.put("resendOf", record.originalCorrelationId());  // explainable duplicate trace
        }
        return headers;
    }

    private CanonicalEnvelope poisonEnvelope(SfdcInboundEvent event) {
        // Minimal envelope for DLQ parking when routing may be unresolved.
        return new CanonicalEnvelope("dlq", Normalizer.SCHEMA_VERSION, SourceSystem.SFDC, event.typeCode(),
                event.notificationId(), event.orgId(), event.sfdcRecordId(), event.applicationRef(),
                event.correlationId(), event.correlationId(), null, event.payloadContentType(), event.receivedAt());
    }

    private void logResend(SfdcInboundEvent event, DedupeResult verdict) {
        // correlationId is per-request (trace); resendOf points at the original.
        log.info("edge.resend notificationId={} correlationId={} resendOf={} path={} nonOwnerDuplicate={}",
                event.notificationId(), event.correlationId(), verdict.originalCorrelationId(),
                verdict.path(), verdict.nonOwnerDuplicate());
    }
}

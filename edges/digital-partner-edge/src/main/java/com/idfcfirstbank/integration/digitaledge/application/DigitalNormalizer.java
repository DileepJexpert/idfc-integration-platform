package com.idfcfirstbank.integration.digitaledge.application;

import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;

import java.time.Clock;
import java.util.function.Supplier;

/**
 * Normalizes a partner request into the SHARED {@link CanonicalEnvelope} — the
 * IDENTICAL shape the SFDC edge produces (only {@code source = DIGITAL} differs).
 * The envelope has no partner field by design: the engine must not be able to
 * tell which channel sent it. The partner is an edge-side concern (status store +
 * a Kafka header), never the shared body.
 */
public class DigitalNormalizer {

    /** Envelope schema tag for this edge (a per-edge field; the engine ignores it). */
    public static final String SCHEMA_VERSION = "digital-partner.v1";

    private final Supplier<String> transactionIdSupplier;
    private final Clock clock;

    public DigitalNormalizer(Supplier<String> transactionIdSupplier, Clock clock) {
        this.transactionIdSupplier = transactionIdSupplier;
        this.clock = clock;
    }

    public CanonicalEnvelope toEnvelope(DigitalOriginationCommand command, String payloadRef) {
        return new CanonicalEnvelope(
                transactionIdSupplier.get(),
                SCHEMA_VERSION,
                SourceSystem.DIGITAL,
                command.type(),
                command.requestId(),          // notificationId == partner request id (primary dedup key)
                command.orgId(),
                null,                          // no sfdcRecordId on the digital channel
                command.applicationRef(),
                command.correlationId(),
                command.correlationId(),       // originalCorrelationId == first correlationId
                payloadRef,
                "application/json",
                clock.instant());
    }
}

package in.idfc.integration.edges.sfdcingress.application;

import in.idfc.integration.edges.sfdcingress.domain.model.CanonicalEnvelope;
import in.idfc.integration.edges.sfdcingress.domain.model.RoutingDecision;
import in.idfc.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import in.idfc.integration.edges.sfdcingress.domain.model.SourceSystem;

import java.util.function.Supplier;

/**
 * Pure mapping from a validated inbound event + resolved routing + claim-check
 * ref to the canonical envelope. No I/O, no business logic. The platform-added
 * fields (transactionId, originalCorrelationId) are on the parity allowlist (§F).
 */
public class Normalizer {

    public static final String SCHEMA_VERSION = "sfdc-ingress.v1";

    private final Supplier<String> transactionIdSupplier;

    public Normalizer(Supplier<String> transactionIdSupplier) {
        this.transactionIdSupplier = transactionIdSupplier;
    }

    public CanonicalEnvelope toEnvelope(SfdcInboundEvent event, RoutingDecision routing,
                                        String payloadRef, String originalCorrelationId) {
        return new CanonicalEnvelope(
                transactionIdSupplier.get(),
                SCHEMA_VERSION,
                SourceSystem.SFDC,
                routing.typeCode(),
                event.notificationId(),
                event.orgId(),
                event.sfdcRecordId(),
                event.applicationRef(),
                event.correlationId(),
                originalCorrelationId,
                payloadRef,
                event.payloadContentType(),
                event.receivedAt());
    }
}

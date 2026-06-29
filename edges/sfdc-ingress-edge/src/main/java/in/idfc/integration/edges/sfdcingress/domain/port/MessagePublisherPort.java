package in.idfc.integration.edges.sfdcingress.domain.port;

import in.idfc.integration.edges.sfdcingress.domain.model.CanonicalEnvelope;
import in.idfc.integration.edges.sfdcingress.domain.model.RoutingDecision;

import java.util.Map;

/**
 * OUT port for publishing the canonical envelope onto the origination transport
 * (Kafka, real & local). Headers carry {@code correlationId}, {@code notificationId}
 * and (on a resend) {@code resendOf} end-to-end. Throws on publish failure so the
 * caller can classify it as transient (C2: do NOT ACK).
 */
public interface MessagePublisherPort {
    void publish(CanonicalEnvelope envelope, RoutingDecision routing, Map<String, String> headers);

    /** Park a message that has been ACKed but cannot be processed (DLQ / poison). */
    void publishToDlq(CanonicalEnvelope envelope, Map<String, String> headers, String reason);
}

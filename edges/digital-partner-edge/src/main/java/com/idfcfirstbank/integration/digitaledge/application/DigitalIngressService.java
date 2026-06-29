package com.idfcfirstbank.integration.digitaledge.application;

import com.idfcfirstbank.integration.digitaledge.domain.port.EnvelopePublisherPort;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The thin digital ingress: dedupe → route → normalize → publish → fast-ACK. NO
 * business logic (that is the engine + capabilities, unchanged). It produces the
 * SAME canonical envelope on the SAME origination topic the SFDC edge uses.
 *
 * <p>Dedupe is two atomic CREATE_ONLY gates (request id, then partner+applicationRef)
 * so a partner resend — exact or new-id-same-application — never double-publishes.
 *
 * <p>Scope note: replaying a message after a transient publish failure is the
 * platform idempotency store's richer status-machine (the SFDC edge's C2/C3),
 * a future extraction to {@code platform-idempotency}; here a publish failure
 * propagates so the partner retries.
 */
public class DigitalIngressService {

    private static final Logger log = LoggerFactory.getLogger(DigitalIngressService.class);

    private final IdempotencyGatePort gate;
    private final EnvelopePublisherPort publisher;
    private final OriginationRouting routing;
    private final DigitalNormalizer normalizer;
    private final ApplicationStatusStore statusStore;

    public DigitalIngressService(IdempotencyGatePort gate, EnvelopePublisherPort publisher,
                                 OriginationRouting routing, DigitalNormalizer normalizer,
                                 ApplicationStatusStore statusStore) {
        this.gate = gate;
        this.publisher = publisher;
        this.routing = routing;
        this.normalizer = normalizer;
        this.statusStore = statusStore;
    }

    public DigitalIngressResult ingest(DigitalOriginationCommand command) {
        String applicationId = applicationId(command);

        // Gate 1: the primary key (partner request id) — exact resend is a no-op.
        if (!gate.claimNotification(command.requestId())) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.ACK_DUPLICATE_REQUEST,
                    "exact resend of request " + command.requestId());
        }

        Optional<String> topic = routing.topicFor(command.type());
        if (topic.isEmpty()) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.UNROUTABLE,
                    "no origination route for type " + command.type());
        }

        // Gate 2: the composite fallback (partner + applicationRef) — new id, same application.
        if (!gate.claimApplication(command.applicationKey(), command.requestId())) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.ACK_DUPLICATE_APPLICATION,
                    "resend of an already-owned application " + command.applicationKey());
        }

        CanonicalEnvelope envelope = normalizer.toEnvelope(command, payloadRef(command));
        publisher.publish(envelope, topic.get()); // throws on transient failure -> 503, partner retries
        statusStore.register(command.applicationRef(), applicationId, command.partner());

        log.info("digital.ingest published partner={} applicationRef={} type={} topic={} applicationId={}",
                command.partner(), command.applicationRef(), command.type(), topic.get(), applicationId);
        return new DigitalIngressResult(applicationId, DigitalDisposition.ACK_PROCESSED,
                "normalized and published to the engine");
    }

    private static String applicationId(DigitalOriginationCommand command) {
        // Deterministic so a resend returns the SAME applicationId to the partner.
        return "DIG-" + command.partner() + "-" + command.applicationRef();
    }

    private static String payloadRef(DigitalOriginationCommand command) {
        // Claim-check placeholder (same pattern as the SFDC edge's S3 ref).
        return "digital-claimcheck://" + command.requestId();
    }
}

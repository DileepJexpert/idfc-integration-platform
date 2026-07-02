package com.idfcfirstbank.integration.digitaledge.application;

import com.idfcfirstbank.integration.digitaledge.domain.port.EnvelopePublisherPort;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort;
import com.idfcfirstbank.integration.digitaledge.domain.port.IdempotencyGatePort.ClaimOutcome;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * The thin digital ingress: route → dedupe → normalize → publish → fast-ACK. NO
 * business logic (that is the engine + capabilities, unchanged). It produces the
 * SAME canonical envelope on the SAME origination topic the SFDC edge uses.
 *
 * <p>Dedupe is two STATUSED claim gates (request id, then partner+applicationRef):
 * a resend is a duplicate only when the prior claim's publish was CONFIRMED or is
 * still live within its lease. A synchronous publish failure RELEASES this
 * attempt's claims before the 503 goes out, so the partner's retry re-drives the
 * publish instead of collecting false duplicate-ACKs against a message that was
 * never sent; a crashed attempt is recovered via the lease (resume/seize).
 * Routing is checked BEFORE any claim so an unroutable request never burns its
 * request id.
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

        // Routing FIRST: an unroutable request must not burn its request id — the
        // partner fixes the type and resends the same id, which must then proceed.
        Optional<String> topic = routing.topicFor(command.type());
        if (topic.isEmpty()) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.UNROUTABLE,
                    "no origination route for type " + command.type());
        }

        // Gate 1: the primary key (partner request id).
        ClaimOutcome requestClaim = gate.claimNotification(command.requestId());
        if (!requestClaim.mayProceed()) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.ACK_DUPLICATE_REQUEST,
                    "resend of request " + command.requestId() + " (" + requestClaim + ")");
        }

        // Gate 2: the composite fallback (partner + applicationRef) — new id, same application.
        ClaimOutcome applicationClaim = gate.claimApplication(command.applicationKey(), command.requestId());
        if (!applicationClaim.mayProceed()) {
            return new DigitalIngressResult(applicationId, DigitalDisposition.ACK_DUPLICATE_APPLICATION,
                    "resend of an already-owned application " + command.applicationKey()
                            + " (" + applicationClaim + ")");
        }
        if (requestClaim == ClaimOutcome.RESUME || applicationClaim == ClaimOutcome.RESUME) {
            log.warn("digital.ingest.resume requestId={} applicationRef={} — re-driving a crashed attempt",
                    command.requestId(), command.applicationRef());
        }

        CanonicalEnvelope envelope = normalizer.toEnvelope(command);
        try {
            publisher.publish(envelope, topic.get());
        } catch (RuntimeException e) {
            // The publish did NOT happen. Release this attempt's claims so the
            // partner's retry (same request id) re-drives cleanly instead of
            // hitting a false duplicate-ACK on a message that was never sent.
            gate.releaseClaims(command.requestId(), command.applicationKey());
            throw e; // -> 503, partner retries
        }
        gate.markPublished(command.requestId());
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
}

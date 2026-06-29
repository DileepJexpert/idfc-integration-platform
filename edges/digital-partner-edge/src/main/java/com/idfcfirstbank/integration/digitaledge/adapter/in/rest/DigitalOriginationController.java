package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

import com.idfcfirstbank.integration.digitaledge.application.DigitalDisposition;
import com.idfcfirstbank.integration.digitaledge.application.DigitalIngressResult;
import com.idfcfirstbank.integration.digitaledge.application.DigitalIngressService;
import com.idfcfirstbank.integration.digitaledge.application.DigitalOriginationCommand;
import com.idfcfirstbank.integration.digitaledge.config.DigitalEdgeProperties.Partner;
import com.idfcfirstbank.integration.digitaledge.config.PartnerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * The thin SYNCHRONOUS partner door (digital twin of the SFDC edge's async SOAP
 * inbound). Authenticate the partner (config token) → validate → hand to the
 * ingress service → fast-ACK with an applicationId. NO business logic; the engine
 * + capabilities run the SAME journey over the SAME canonical envelope.
 */
@RestController
@RequestMapping("/api/v1/digital")
public class DigitalOriginationController {

    private static final Logger log = LoggerFactory.getLogger(DigitalOriginationController.class);

    private final DigitalIngressService ingressService;
    private final PartnerRegistry partnerRegistry;

    public DigitalOriginationController(DigitalIngressService ingressService, PartnerRegistry partnerRegistry) {
        this.ingressService = ingressService;
        this.partnerRegistry = partnerRegistry;
    }

    @PostMapping("/origination")
    public ResponseEntity<DigitalAck> originate(
            @RequestHeader(value = "X-Partner-Token", required = false) String partnerToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody DigitalOriginationRequest request) {

        Optional<Partner> partner = partnerRegistry.resolveByToken(partnerToken);
        if (partner.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new DigitalAck(null, "UNAUTHENTICATED", "unknown or missing partner token"));
        }
        if (!request.isStructurallyValid()) {
            return ResponseEntity.badRequest()
                    .body(new DigitalAck(null, "INVALID", "requestId, applicationRef, type and orgId are required"));
        }

        DigitalOriginationCommand command = new DigitalOriginationCommand(
                partner.get().code(), request.requestId(), request.applicationRef(), request.type(),
                request.orgId(), correlationId == null ? "corr-" + UUID.randomUUID() : correlationId,
                request.payload());

        try {
            DigitalIngressResult result = ingressService.ingest(command);
            HttpStatus status = result.disposition() == DigitalDisposition.UNROUTABLE
                    ? HttpStatus.UNPROCESSABLE_ENTITY
                    : HttpStatus.OK;
            return ResponseEntity.status(status)
                    .body(new DigitalAck(result.applicationId(), result.disposition().name(), result.reason()));
        } catch (RuntimeException e) {
            // Transient (e.g. broker down): do NOT ACK — the partner retries.
            log.error("digital.origination transient failure for request {}", request.requestId(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new DigitalAck(null, "RETRY", "transient failure; please retry"));
        }
    }
}

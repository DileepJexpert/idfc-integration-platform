package in.idfc.integration.edges.sfdcingress.adapter.in.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.idfc.integration.edges.sfdcingress.application.EdgeDisposition;
import in.idfc.integration.edges.sfdcingress.application.EdgeResult;
import in.idfc.integration.edges.sfdcingress.application.SfdcIngressService;
import in.idfc.integration.edges.sfdcingress.domain.model.CanonicalEnvelope;
import in.idfc.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import in.idfc.integration.edges.sfdcingress.domain.model.SourceSystem;
import in.idfc.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import in.idfc.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/**
 * The thin inbound edge: authenticate → validate → hand to the ingress service
 * (dedupe → normalize → route → fast-ACK). NO business logic here.
 *
 * <p>Transport ACK mapping encodes C2: a disposition that {@code acknowledges()}
 * returns HTTP 200 (SFDC stops redelivering); a transient disposition returns
 * HTTP 503 so SFDC redelivers. A provably-permanent schema-invalid body is ACKed
 * (200) and parked in the DLQ rather than rejected — retrying it would never help.
 */
@RestController
@RequestMapping("/api/v1/sfdc")
public class SfdcIngressController {

    private static final Logger log = LoggerFactory.getLogger(SfdcIngressController.class);

    private final SfdcIngressService ingressService;
    private final AuthTokenPort authTokenPort;
    private final MessagePublisherPort publisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SfdcIngressController(SfdcIngressService ingressService, AuthTokenPort authTokenPort,
                                 MessagePublisherPort publisher, ObjectMapper objectMapper, Clock clock) {
        this.ingressService = ingressService;
        this.authTokenPort = authTokenPort;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping("/notifications")
    public ResponseEntity<EdgeResponse> ingest(
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
            @RequestBody SfdcNotificationRequest request) {

        if (!authTokenPort.authenticate(authToken)) {
            // Auth failure is not a redelivery concern — reject outright.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new EdgeResponse(request.notificationId(), "UNAUTHENTICATED",
                            "invalid or missing token", request.correlationId()));
        }

        if (!request.isStructurallyValid()) {
            return permanentSchemaInvalid(request);
        }

        SfdcInboundEvent event = new SfdcInboundEvent(
                request.notificationId(), request.correlationId(), request.sfdcRecordId(),
                request.applicationRef(), request.orgId(), request.type(),
                payloadBytes(request), "application/json", clock.instant());

        EdgeResult result = ingressService.ingest(event);
        HttpStatus status = result.acknowledges() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new EdgeResponse(
                result.notificationId(), result.disposition().name(), result.reason(), request.correlationId()));
    }

    /** C2: schema-invalid is provably permanent — ACK (200) + park in DLQ. */
    private ResponseEntity<EdgeResponse> permanentSchemaInvalid(SfdcNotificationRequest request) {
        CanonicalEnvelope dlq = new CanonicalEnvelope("dlq", "sfdc-ingress.v1", SourceSystem.SFDC,
                request.type(), request.notificationId(), request.orgId(), request.sfdcRecordId(),
                request.applicationRef(), request.correlationId(), request.correlationId(),
                null, "application/json", clock.instant());
        try {
            publisher.publishToDlq(dlq, Map.of("correlationId", String.valueOf(request.correlationId())),
                    "schema-invalid: missing required field(s)");
        } catch (RuntimeException e) {
            log.error("edge.dlq-publish-failed for schema-invalid notificationId={}", request.notificationId(), e);
        }
        log.error("edge.schema-invalid notificationId={} ACK+DLQ (C2 permanent) ALERT", request.notificationId());
        return ResponseEntity.ok(new EdgeResponse(request.notificationId(),
                EdgeDisposition.ACK_DLQ_PERMANENT.name(), "schema-invalid; parked in DLQ", request.correlationId()));
    }

    private byte[] payloadBytes(SfdcNotificationRequest request) {
        try {
            return objectMapper.writeValueAsBytes(request.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unserializable payload", e);
        }
    }
}

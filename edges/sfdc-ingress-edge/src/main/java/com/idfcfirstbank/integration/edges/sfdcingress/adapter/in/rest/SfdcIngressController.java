package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest;

import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SoapAck;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SoapParseException;
import com.idfcfirstbank.integration.edges.sfdcingress.application.BatchAck;
import com.idfcfirstbank.integration.edges.sfdcingress.application.BatchIngestService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The thin inbound edge: authenticate → hand the raw SOAP Outbound Message to the
 * batch ingest service (parse → un-batch → normalize → dedupe → route → publish)
 * → return the SOAP {@code <Ack>}. NO business logic here; NO SOAP parsing here.
 *
 * <p>The engine never sees SOAP — this controller is the only place the SFDC
 * transport shape enters, and it leaves as N canonical envelopes.
 *
 * <p>ACK mapping (normalisation spec §5):
 * <ul>
 *   <li>whole batch durably accepted → HTTP 200 + {@code <Ack>true}</li>
 *   <li>any transient failure → HTTP 200 + {@code <Ack>false} (SFDC resends the
 *       entire batch; per-{@code Notification/Id} dedup skips the ones that landed)</li>
 *   <li>unparseable ENVELOPE → HTTP 500 + SOAP fault (cannot even un-batch)</li>
 *   <li>bad token → HTTP 401 + SOAP fault</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sfdc")
public class SfdcIngressController {

    private static final Logger log = LoggerFactory.getLogger(SfdcIngressController.class);

    private final BatchIngestService batchIngestService;
    private final AuthTokenPort authTokenPort;

    public SfdcIngressController(BatchIngestService batchIngestService, AuthTokenPort authTokenPort) {
        this.batchIngestService = batchIngestService;
        this.authTokenPort = authTokenPort;
    }

    @PostMapping(
            value = "/outbound-messages",
            consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE, "application/soap+xml"},
            produces = MediaType.TEXT_XML_VALUE)
    public ResponseEntity<String> receive(
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
            @RequestBody String soapXml) {

        if (!authTokenPort.authenticate(authToken)) {
            return xml(HttpStatus.UNAUTHORIZED, SoapAck.fault("invalid or missing token"));
        }

        try {
            BatchAck ack = batchIngestService.ingestBatch(soapXml);
            // HTTP 200 for both true/false: the <Ack> element carries the retry signal.
            return xml(HttpStatus.OK, SoapAck.ack(ack.accepted()));
        } catch (SoapParseException e) {
            // Whole-batch parse failure — do NOT ACK; SFDC resends the entire message.
            log.error("edge.soap-unparseable ACK-withheld reason={}", e.getMessage());
            return xml(HttpStatus.INTERNAL_SERVER_ERROR, SoapAck.fault("unparseable envelope: " + e.getMessage()));
        }
    }

    private static ResponseEntity<String> xml(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_XML).body(body);
    }
}

package com.idfcfirstbank.integration.edges.sfdcingress.application;

import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.NotificationMappingException;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.OutboundNotificationMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SfdcOutboundMessage;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SfdcOutboundMessageParser;
import com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap.SoapNotification;
import com.idfcfirstbank.integration.shared.domain.envelope.CanonicalEnvelope;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.SfdcInboundEvent;
import com.idfcfirstbank.integration.shared.domain.envelope.SourceSystem;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.MessagePublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The SOAP front-end of the SFDC edge: parse the Outbound Message, un-batch it,
 * and drive EACH notification through the existing {@link SfdcIngressService}
 * (dedupe → normalize → route → claim-check → publish → DLQ). Computes the
 * all-or-nothing SOAP {@code <Ack>} (normalisation spec §2/§5). NO business logic —
 * it only fans the batch out and folds the per-notification results back together.
 *
 * <p>A single {@code <Notification>} with a bad {@code Request__c} CDATA JSON is
 * parked in the DLQ individually and still counts as accepted (permanent — a
 * resend would carry the same bytes); it does NOT sink the batch (spec §6). An
 * unparseable ENVELOPE is a different beast: {@link SfdcOutboundMessageParser}
 * throws and the whole POST is NAK'd upstream (no ACK).
 */
public class BatchIngestService {

    private static final Logger log = LoggerFactory.getLogger(BatchIngestService.class);

    private final SfdcOutboundMessageParser parser;
    private final OutboundNotificationMapper mapper;
    private final SfdcIngressService ingressService;
    private final MessagePublisherPort publisher;
    private final Clock clock;

    public BatchIngestService(SfdcOutboundMessageParser parser, OutboundNotificationMapper mapper,
                              SfdcIngressService ingressService, MessagePublisherPort publisher, Clock clock) {
        this.parser = parser;
        this.mapper = mapper;
        this.ingressService = ingressService;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Parse + un-batch + ingest each notification; fold into an all-or-nothing ack. */
    public BatchAck ingestBatch(String soapXml) {
        SfdcOutboundMessage message = parser.parse(soapXml);   // throws SoapParseException on a bad envelope
        List<SoapNotification> notifications = message.notifications();
        log.info("edge.soap-batch received notifications={} orgId={} actionId={}",
                notifications.size(), message.organizationId(), message.actionId());

        List<EdgeResult> results = new ArrayList<>(notifications.size());
        boolean accepted = true;
        for (SoapNotification n : notifications) {
            EdgeResult result = ingestOne(n, message);
            results.add(result);
            accepted &= result.acknowledges();
        }

        int acknowledged = (int) results.stream().filter(EdgeResult::acknowledges).count();
        log.info("edge.soap-batch done total={} acknowledged={} ack={}",
                notifications.size(), acknowledged, accepted);
        return new BatchAck(notifications.size(), acknowledged, accepted, results);
    }

    private EdgeResult ingestOne(SoapNotification n, SfdcOutboundMessage message) {
        try {
            SfdcInboundEvent event = mapper.toEvent(n, message);
            return ingressService.ingest(event);
        } catch (NotificationMappingException e) {
            // Provably-permanent for THIS notification: DLQ + ACK, don't fail the batch.
            publisher.publishToDlq(mappingDlqEnvelope(n, message),
                    Map.of("notificationId", nullSafe(n.id()), "orgId", nullSafe(message.organizationId())),
                    "mapping-permanent: " + e.getMessage());
            log.error("edge.soap-notification-invalid notificationId={} reason={} ACK+DLQ ALERT",
                    n.id(), e.getMessage());
            return new EdgeResult(EdgeDisposition.ACK_DLQ_PERMANENT, n.id(),
                    "unmappable notification parked in DLQ: " + e.getMessage());
        } catch (RuntimeException e) {
            // Per-notification ISOLATION: an unexpected error (e.g. store/kafka blip that
            // escaped SfdcIngressService's own transient handling) must NOT abort the batch
            // loop and discard the other notifications' ACK bookkeeping. Treat it as transient
            // for THIS notification only — acknowledges()==false makes the whole batch NAK, so
            // SFDC resends and per-Notification/Id dedupe skips the ones that already landed.
            // Log the throwable (store/kafka cause — not the business body) for diagnosis.
            log.error("edge.soap-notification-error notificationId={} (transient; batch will NAK) ALERT",
                    n.id(), e);
            return new EdgeResult(EdgeDisposition.RETRY_TRANSIENT, n.id(),
                    "unexpected edge error; not acknowledged so SFDC redelivers");
        }
    }

    private CanonicalEnvelope mappingDlqEnvelope(SoapNotification n, SfdcOutboundMessage message) {
        return new CanonicalEnvelope("dlq", "sfdc-ingress.v1", SourceSystem.SFDC, n.svcName(),
                n.id(), message.organizationId(), n.sfdcRecordId(), null, null, null,
                null, "application/json", clock.instant());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}

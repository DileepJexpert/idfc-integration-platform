package com.idfcfirstbank.integration.capabilities.communications.application;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.CommsHubPort;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SendMeterPort;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SentSmsStorePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The SENDSMS action: read the OPAQUE Salesforce Task body the edge carried (this
 * capability OWNS the SENDSMS payload contract — Mobile__c/Description), dedupe on
 * the SFDC Notification/Id (a redelivery must not re-send an OTP), and send exactly
 * once through the shared CommsHub — via the {@link SendMeterPort} so a burst can't
 * flood the internal shared resource. NO OTP/body content is ever logged (PII).
 */
@Service
public class CommunicationsService {

    private static final Logger log = LoggerFactory.getLogger(CommunicationsService.class);

    private final CommsHubPort commsHub;
    private final SendMeterPort meter;
    private final SentSmsStorePort store;

    public CommunicationsService(CommsHubPort commsHub, SendMeterPort meter, SentSmsStorePort store) {
        this.commsHub = commsHub;
        this.meter = meter;
        this.store = store;
    }

    /** Handle one canonical envelope (as a map) routed to the SMS topic. */
    public void onSmsRequest(Map<String, Object> envelope) {
        String notificationId = str(envelope.get("notificationId"));
        Object body = envelope.get("payload");
        if (!(body instanceof Map<?, ?> payload)) {
            log.warn("comm.sms.malformed notificationId={} — no inline payload", notificationId);
            return;
        }
        String to = str(payload.get("Mobile__c"));
        String message = str(payload.get("Description"));
        if (to == null || to.isBlank()) {
            log.warn("comm.sms.malformed notificationId={} — no Mobile__c in Task body", notificationId);
            return;
        }
        // Idempotent send: the same Notification/Id must never re-send an OTP.
        if (notificationId != null && !store.markSentIfAbsent(notificationId)) {
            log.info("comm.sms.duplicate notificationId={} — already sent, skipping", notificationId);
            return;
        }
        // Metered: bound concurrent calls to the shared CommsHub (Diwali-burst safe).
        meter.meter(() -> commsHub.sendSms(to, message));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

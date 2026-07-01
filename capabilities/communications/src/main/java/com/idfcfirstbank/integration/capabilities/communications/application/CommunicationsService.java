package com.idfcfirstbank.integration.capabilities.communications.application;

import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SentSmsStorePort;
import com.idfcfirstbank.integration.capabilities.communications.domain.port.out.SmsSenderPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The SENDSMS action: read the OPAQUE Salesforce Task body the edge carried
 * (this capability OWNS the SENDSMS payload contract — Mobile__c/Description),
 * dedupe on the SFDC Notification/Id (a redelivery must not re-send an OTP), and
 * send exactly once. NO OTP/body content is ever logged (PII).
 */
@Service
public class CommunicationsService {

    private static final Logger log = LoggerFactory.getLogger(CommunicationsService.class);

    private final SmsSenderPort sender;
    private final SentSmsStorePort store;

    public CommunicationsService(SmsSenderPort sender, SentSmsStorePort store) {
        this.sender = sender;
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
        sender.send(to, message);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

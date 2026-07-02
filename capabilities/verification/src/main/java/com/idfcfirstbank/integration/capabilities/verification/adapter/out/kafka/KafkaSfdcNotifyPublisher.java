package com.idfcfirstbank.integration.capabilities.verification.adapter.out.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.capabilities.verification.config.VerificationProperties;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.SfdcNotifyPort;
import com.idfcfirstbank.integration.platform.messaging.KafkaDelivery;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Notify SFDC of a terminal verification failure by publishing to the SFDC Response egress
 * capability's notify topic (§B). Carries the routing identity (correlationId/orgId) so the
 * egress capability picks the right per-org response. PII: logs ids + reason only.
 */
@Component
public class KafkaSfdcNotifyPublisher implements SfdcNotifyPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaSfdcNotifyPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String notifyTopic;

    public KafkaSfdcNotifyPublisher(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper,
                                    VerificationProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.notifyTopic = properties.sfdcNotifyTopic();
    }

    @Override
    public void notifyFailure(CapabilityRequest request, String reason) {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("correlationId", request.correlationId());
        notification.put("svcName", request.operation());
        notification.put("outcome", "FAILED");
        notification.put("reason", reason);
        Object orgId = request.payload() == null ? null : request.payload().get("orgId");
        notification.put("orgId", orgId);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(notification);
        } catch (JsonProcessingException e) {
            log.error("verify.notify-sfdc.serialise-failed correlationId={} (cause={})",
                    request.correlationId(), e.getClass().getName());
            return;
        }
        KafkaDelivery.confirm(kafka.send(notifyTopic, request.correlationId(), payload));
        log.warn("verify.notify-sfdc correlationId={} svcName={} reason={}",
                request.correlationId(), request.operation(), reason);
    }
}

package com.idfcfirstbank.integration.digitaledge.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.digitaledge.application.ApplicationStatusStore;
import com.idfcfirstbank.integration.digitaledge.domain.port.PartnerCallbackPort;
import com.idfcfirstbank.integration.platform.messaging.PoisonMessageException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IN adapter: consumes the engine's decision topic and pushes the decision back
 * to the originating partner — the digital-channel mirror of SFDC's push-back.
 * Only acts on applications THIS edge published (the status store owns them), so
 * SFDC-originated decisions are ignored here.
 */
@Component
public class DecisionConsumer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ApplicationStatusStore statusStore;
    private final PartnerCallbackPort partnerCallback;
    private final ObjectMapper objectMapper;

    public DecisionConsumer(ApplicationStatusStore statusStore, PartnerCallbackPort partnerCallback,
                            ObjectMapper objectMapper) {
        this.statusStore = statusStore;
        this.partnerCallback = partnerCallback;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${idfc.digital-edge.decision-topic:orig.decision.v1}",
            groupId = "${idfc.digital-edge.decision-group:digital-partner-edge-decisions}")
    public void onMessage(String decisionJson) {
        Map<String, Object> decision;
        try {
            decision = objectMapper.readValue(decisionJson, MAP);
        } catch (Exception e) {
            throw new PoisonMessageException("digital.decision could not deserialize", e);
        }

        // Only act on DIGITAL-originated decisions; the SFDC edge owns its own.
        // (The status store already scopes us to apps we published; this is the
        // explicit, cheap guard now that the decision carries its source.)
        String source = str(decision.get("source"));
        if (source != null && !"DIGITAL".equalsIgnoreCase(source)) {
            return;
        }
        String applicationRef = str(decision.get("applicationRef"));
        String outcome = str(decision.get("outcome"));
        String loanId = str(decision.get("loanId"));
        statusStore.recordDecision(applicationRef, outcome, loanId).ifPresent(status ->
                partnerCallback.pushDecision(status.partner(), applicationRef, outcome, loanId,
                        str(decision.get("correlationId"))));
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

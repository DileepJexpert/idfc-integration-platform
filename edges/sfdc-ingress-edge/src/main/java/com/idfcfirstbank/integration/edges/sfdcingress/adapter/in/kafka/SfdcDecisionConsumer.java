package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.kafka;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idfcfirstbank.integration.edges.sfdcingress.application.DecisionService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * IN adapter: consumes the engine's decision topic and closes the SFDC push-back
 * loop over Kafka (the REST {@code /decisions} endpoint remains for manual use).
 *
 * <p>The decision now carries the originating edge's {@code source} and the
 * {@code notificationId}, so this consumer:
 * <ol>
 *   <li>ignores decisions that did not originate at the SFDC edge (the digital
 *       edge has its own consumer), and</li>
 *   <li>CASes the idempotency record by {@code notificationId} via
 *       {@link DecisionService}, which fires the SFDC callback EXACTLY on the
 *       transition into DECIDED (never on a resend) — the C1 ownership guard.</li>
 * </ol>
 */
@Component
public class SfdcDecisionConsumer {

    private static final Logger log = LoggerFactory.getLogger(SfdcDecisionConsumer.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private static final String SFDC = "SFDC";

    private final DecisionService decisionService;
    private final ObjectMapper objectMapper;

    public SfdcDecisionConsumer(DecisionService decisionService, ObjectMapper objectMapper) {
        this.decisionService = decisionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${idfc.edge.decision-topic:orig.decision.v1}",
            groupId = "${idfc.edge.decision-group:sfdc-ingress-edge-decisions}")
    public void onMessage(String decisionJson) {
        try {
            Map<String, Object> decision = objectMapper.readValue(decisionJson, MAP);
            String source = str(decision.get("source"));
            String notificationId = str(decision.get("notificationId"));

            // Only act on SFDC-originated decisions; the digital edge owns its own.
            if (!SFDC.equalsIgnoreCase(source) || notificationId == null) {
                return;
            }

            String outcome = str(decision.get("outcome"));
            String loanId = str(decision.get("loanId"));
            String correlationId = str(decision.get("correlationId"));

            boolean pushed = decisionService.applyDecision(
                    notificationId, new Decision(outcome, loanId, null), correlationId);
            log.info("sfdc.decision notificationId={} outcome={} loanId={} pushed={}",
                    notificationId, outcome, loanId, pushed);
        } catch (Exception e) {
            log.error("sfdc.decision could not process: {}", decisionJson, e);
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}

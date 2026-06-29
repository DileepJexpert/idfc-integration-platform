package com.idfcfirstbank.integration.capabilities.scoring.application;

import com.idfcfirstbank.integration.capabilities.scoring.domain.model.ScoringDecision;
import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;
import com.idfcfirstbank.integration.capabilities.scoring.domain.service.DecisionRule;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free handler: the DECISIONING capability. Reads the bureau score from
 * the upstream bureau node's result ({@code collectedResults}), enriches via
 * {@link FicoPort}, applies the pure {@link DecisionRule}, and maps the
 * {@link ScoringDecision} into a contract {@link CapabilityResponse}. On any
 * failure it returns {@link CapabilityStatus#ERROR} (the engine fails the journey).
 */
public class ScoringService {

    private static final Logger log = LoggerFactory.getLogger(ScoringService.class);

    private final FicoPort fico;
    private final DecisionRule rule;
    private final int threshold;

    public ScoringService(FicoPort fico, DecisionRule rule, int threshold) {
        this.fico = fico;
        this.rule = rule;
        this.threshold = threshold;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            Map<String, Object> payload = request.payload() == null ? Map.of() : request.payload();

            Object bureau = request.collectedResults().get("bureau");
            int bureauScore = (bureau instanceof Map<?, ?> m && m.get("bureauScore") instanceof Number n)
                    ? n.intValue() : 0;

            List<String> negativeFlags = (payload.get("negativeFlags") instanceof List<?> l)
                    ? l.stream().map(String::valueOf).toList() : List.of();

            int ficoScore = fico.score(payload);

            ScoringDecision decision = rule.decide(bureauScore, negativeFlags, threshold);

            List<String> reasons = new ArrayList<>(decision.reasons());
            reasons.add("fico=" + ficoScore);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("decision", decision.decision());
            result.put("score", decision.score());
            result.put("reasons", reasons);
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("scoring.decide failed for instance {}", request.journeyInstanceId(), e);
            return error(request);
        }
    }

    private static CapabilityResponse ok(CapabilityRequest req, Map<String, Object> result) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.OK, result);
    }

    private static CapabilityResponse error(CapabilityRequest req) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.ERROR, Map.of());
    }
}

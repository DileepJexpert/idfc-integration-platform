package com.idfcfirstbank.integration.capabilities.scoring.domain.service;

import com.idfcfirstbank.integration.capabilities.scoring.domain.model.ScoringDecision;

import java.util.ArrayList;
import java.util.List;

/**
 * PURE decision rule (no framework, no I/O) — the heart of the scoring capability
 * and the part that must be unit-tested in isolation. APPROVED when the bureau
 * score meets the threshold AND there are no negative flags; otherwise REJECTED.
 * The score on the decision is the bureau score.
 */
public class DecisionRule {

    public ScoringDecision decide(int bureauScore, List<String> negativeFlags, int threshold) {
        List<String> flags = negativeFlags == null ? List.of() : negativeFlags;
        List<String> reasons = new ArrayList<>();

        boolean meetsThreshold = bureauScore >= threshold;
        boolean clean = flags.isEmpty();

        if (meetsThreshold) {
            reasons.add("bureauScore " + bureauScore + " >= threshold " + threshold);
        } else {
            reasons.add("bureauScore " + bureauScore + " < threshold " + threshold);
        }
        if (!clean) {
            reasons.add("negative flags: " + flags);
        }

        String decision = (meetsThreshold && clean) ? ScoringDecision.APPROVED : ScoringDecision.REJECTED;
        return new ScoringDecision(decision, bureauScore, reasons);
    }
}

package com.idfcfirstbank.integration.capabilities.scoring.domain.model;

import java.util.List;

/**
 * The credit decision produced by the scoring capability. {@code decision} is the
 * field the engine's branch node routes on ({@code decision == 'APPROVED'} /
 * {@code decision == 'REJECTED'}). {@code reasons} explains the outcome.
 */
public record ScoringDecision(String decision, int score, List<String> reasons) {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}

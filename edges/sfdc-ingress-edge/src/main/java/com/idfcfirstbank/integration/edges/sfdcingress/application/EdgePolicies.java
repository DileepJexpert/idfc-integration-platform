package com.idfcfirstbank.integration.edges.sfdcingress.application;

/**
 * Tunable edge ceilings (config-driven; defaults from the punch list). Kept as a
 * plain value so the application service has no Spring dependency.
 *
 * @param poisonRedeliveryThreshold C5: redeliveries of one dedup key that never
 *                                  reach a clean publish before poison (default 5)
 * @param maxJourneyRetry           C3: automatic transient journey re-enqueues
 *                                  before DLQ-for-ops (default 1)
 */
public record EdgePolicies(int poisonRedeliveryThreshold, int maxJourneyRetry) {
    public static EdgePolicies defaults() {
        return new EdgePolicies(5, 1);
    }
}

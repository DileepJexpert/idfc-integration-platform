package com.idfcfirstbank.integration.capabilities.scoring.domain.port;

import java.util.Map;

/**
 * OUT port to FICO (the scoring vendor). The real adapter is HTTP (URL via
 * config); the mock adapter scores locally. The domain never knows which is
 * wired. Returns a fico score used to enrich the decision reasons.
 */
public interface FicoPort {
    /** Score the applicant payload (returns a FICO score used as enrichment/reason). */
    int score(Map<String, Object> payload);
}

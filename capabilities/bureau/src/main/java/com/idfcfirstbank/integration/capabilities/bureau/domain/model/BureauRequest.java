package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.util.List;
import java.util.Map;

/**
 * A canonical bureau request: the applicant identity, WHICH bureaus to pull,
 * the purpose, and the consent reference (a bureau pull is consented + audited).
 */
public record BureauRequest(
        Map<String, Object> identity,
        List<BureauType> bureauTypes,
        String purpose,
        String consentRef) {

    public BureauRequest {
        identity = identity == null ? Map.of() : identity;
        bureauTypes = bureauTypes == null || bureauTypes.isEmpty() ? List.of(BureauType.CIBIL) : List.copyOf(bureauTypes);
    }
}

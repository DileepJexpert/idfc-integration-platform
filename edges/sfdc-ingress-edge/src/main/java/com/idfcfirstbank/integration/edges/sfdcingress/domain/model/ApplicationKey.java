package com.idfcfirstbank.integration.edges.sfdcingress.domain.model;

import java.util.Objects;

/**
 * The composite-fallback dedup key (punch list §A/§B): derived from the STABLE
 * pair {@code sfdcRecordId + applicationRef}. This is load-bearing — it catches
 * a resend that arrives with a NEW notificationId but the SAME business
 * application, so it must NOT double-book. {@code applicationRef} MUST come from
 * an SFDC field that is stable across a resend of the same application; deriving
 * it from anything request-scoped breaks this guarantee.
 */
public record ApplicationKey(String value) {
    public ApplicationKey {
        Objects.requireNonNull(value, "application key value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("application key must not be blank");
        }
    }

    /** Stable, order-fixed composition of the two business-stable identifiers. */
    public static ApplicationKey of(String sfdcRecordId, String applicationRef) {
        if (sfdcRecordId == null || sfdcRecordId.isBlank()
                || applicationRef == null || applicationRef.isBlank()) {
            throw new IllegalArgumentException(
                    "composite fallback requires both sfdcRecordId and applicationRef");
        }
        return new ApplicationKey(sfdcRecordId.trim() + "::" + applicationRef.trim());
    }
}

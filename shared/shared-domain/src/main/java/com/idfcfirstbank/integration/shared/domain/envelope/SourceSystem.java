package com.idfcfirstbank.integration.shared.domain.envelope;

/**
 * The channel an origination event entered through. Shared across edges so the
 * engine sees one envelope type regardless of door: {@code SFDC} (assisted) and
 * {@code DIGITAL} (fintech partner) publish the SAME {@link CanonicalEnvelope}.
 */
public enum SourceSystem {
    /** Assisted channel — Salesforce outbound messages (sfdc-ingress-edge). */
    SFDC,
    /** Digital channel — fintech partner REST (digital-partner-edge). */
    DIGITAL
}

package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

import java.util.List;

/**
 * A parsed SFDC SOAP Outbound Message: the envelope metadata plus the 1..100
 * {@code <Notification>} blocks it batches. One SOAP POST = up to 100 requests;
 * the edge un-batches this into N journey instances (BRD/normalisation spec §2).
 *
 * @param organizationId {@code <OrganizationId>} — tenant/org (→ CanonicalRequest.orgId)
 * @param actionId       {@code <ActionId>} — the outbound-message action
 * @param sessionId      {@code <SessionId>} — SFDC session (auth is via the edge's own token/mTLS)
 * @param enterpriseUrl  {@code <EnterpriseUrl>}
 * @param partnerUrl     {@code <PartnerUrl>}
 * @param notifications  the un-batched {@code <Notification>} list (never null)
 */
public record SfdcOutboundMessage(
        String organizationId,
        String actionId,
        String sessionId,
        String enterpriseUrl,
        String partnerUrl,
        List<SoapNotification> notifications) {

    public SfdcOutboundMessage {
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
    }
}

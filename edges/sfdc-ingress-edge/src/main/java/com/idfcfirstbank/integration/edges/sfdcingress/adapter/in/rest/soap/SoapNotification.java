package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

/**
 * One parsed {@code <Notification>} from an SFDC SOAP Outbound Message — the raw,
 * un-normalised shape straight off the wire. The business payload is still the
 * {@code Request__c} CDATA text (JSON as a string); it is unwrapped and mapped to
 * the canonical event by {@link OutboundNotificationMapper}, not here. This record
 * is framework-free and carries no interpretation.
 *
 * @param id           {@code <Notification><Id>} — the dedup key (SFDC resends it verbatim)
 * @param sfdcRecordId {@code sObject/sf1:Id} — back-reference to the SFDC record
 * @param clientId     {@code CLIENTID__c} (e.g. {@code SFDC})
 * @param svcName      {@code SVCNAME__c} — the ROUTING key (→ journeyKey via config)
 * @param version      {@code VERSION__c} — contract version
 * @param execMode     {@code EXECMODE__c} — sync/async hint
 * @param requestJson  {@code Request__c} CDATA text — the business payload as JSON
 */
public record SoapNotification(
        String id,
        String sfdcRecordId,
        String clientId,
        String svcName,
        String version,
        String execMode,
        String requestJson) {
}

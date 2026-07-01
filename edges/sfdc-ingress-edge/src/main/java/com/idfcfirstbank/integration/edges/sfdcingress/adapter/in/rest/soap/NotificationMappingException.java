package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest.soap;

/**
 * One {@code <Notification>} could not be normalised — a missing required field
 * or an unparseable {@code Request__c} CDATA JSON. This is provably PERMANENT for
 * that notification (a resend would carry the same bad bytes), so the edge parks
 * it in the DLQ and still ACKs it — WITHOUT failing the rest of the batch (spec §6).
 */
public class NotificationMappingException extends RuntimeException {
    public NotificationMappingException(String message) {
        super(message);
    }

    public NotificationMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}

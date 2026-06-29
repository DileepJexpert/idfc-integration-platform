package com.idfcfirstbank.integration.digitaledge.application;

/** The fast-ACK the partner receives: an applicationId + the edge's disposition. */
public record DigitalIngressResult(String applicationId, DigitalDisposition disposition, String reason) {
}

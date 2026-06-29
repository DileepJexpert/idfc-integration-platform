package com.idfcfirstbank.integration.edges.sfdcingress.application;

/** Outcome of handling one inbound event, plus a human-readable reason for ops. */
public record EdgeResult(EdgeDisposition disposition, String notificationId, String reason) {
    public boolean acknowledges() {
        return disposition.acknowledges();
    }
}

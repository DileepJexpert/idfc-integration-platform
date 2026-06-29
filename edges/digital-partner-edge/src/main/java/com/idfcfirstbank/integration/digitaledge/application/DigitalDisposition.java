package com.idfcfirstbank.integration.digitaledge.application;

/**
 * What the edge did with a partner request. The inbound adapter maps this to an
 * HTTP status: an {@code acknowledges()} disposition is 200 (the partner stops
 * retrying); {@code UNROUTABLE} is 422; a transient publish failure is 503.
 */
public enum DigitalDisposition {
    ACK_PROCESSED(true),               // new winner: normalized + published to the engine
    ACK_DUPLICATE_REQUEST(true),       // exact resend (same request id): idempotent, no publish
    ACK_DUPLICATE_APPLICATION(true),   // resend with a new id but same application: no double-book
    UNROUTABLE(true);                  // no route for (source, type): permanent, partner-fixable

    private final boolean ack;

    DigitalDisposition(boolean ack) {
        this.ack = ack;
    }

    public boolean acknowledges() {
        return ack;
    }
}

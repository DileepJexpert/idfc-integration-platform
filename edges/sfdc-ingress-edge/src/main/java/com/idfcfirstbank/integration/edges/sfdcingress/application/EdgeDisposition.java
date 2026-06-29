package com.idfcfirstbank.integration.edges.sfdcingress.application;

/**
 * What the edge decided to do with an inbound event. The inbound adapter maps
 * this to a transport ACK/no-ACK (HTTP 2xx vs 5xx). The C2 rule is encoded here:
 * only provably-permanent and poison-broken outcomes ACK; transient does NOT.
 */
public enum EdgeDisposition {
    ACK_PROCESSED(true),            // new winner: normalized + published
    ACK_DUPLICATE_INFLIGHT(true),   // resend while in-flight: re-attached, no publish
    ACK_DUPLICATE_DECIDED(true),    // resend after decision: idempotent, no push
    ACK_DLQ_PERMANENT(true),        // C2 permanent: ACK + DLQ + alert
    ACK_DLQ_POISON(true),           // C5 breaker tripped: ACK + DLQ-as-poison + alert
    REENQUEUED(true),               // C3 transient journey re-enqueue (<= ceiling)
    RETRY_TRANSIENT(false);         // C2 transient: do NOT ACK, let SFDC redeliver

    private final boolean ack;

    EdgeDisposition(boolean ack) {
        this.ack = ack;
    }

    /** true => transport should ACK (HTTP 2xx); false => signal redelivery (5xx). */
    public boolean acknowledges() {
        return ack;
    }
}

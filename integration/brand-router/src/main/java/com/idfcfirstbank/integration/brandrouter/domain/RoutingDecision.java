package com.idfcfirstbank.integration.brandrouter.domain;

/** Where a brand message routes. KAFKA: produce to the response topic keyed by
 * brand; ACTIVEMQ: convert to XML and produce to the queue. */
public record RoutingDecision(Target target, String key, String payload) {
    public enum Target { KAFKA, ACTIVEMQ }
}

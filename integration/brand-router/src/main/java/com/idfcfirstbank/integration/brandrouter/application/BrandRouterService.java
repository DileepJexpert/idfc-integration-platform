package com.idfcfirstbank.integration.brandrouter.application;

import com.idfcfirstbank.integration.brandrouter.domain.RoutingDecision;

import java.util.Set;

/**
 * PURE routing logic (BRD §6): a partitioned brand routes to Kafka (keyed by
 * brand); any other brand converts to XML and routes to ActiveMQ. No I/O — the
 * adapters do the producing; this is fully unit-testable.
 */
public final class BrandRouterService {

    public RoutingDecision route(String brandName, String message, Set<String> partitions) {
        if (brandName != null && partitions.contains(brandName)) {
            return new RoutingDecision(RoutingDecision.Target.KAFKA, brandName, message);
        }
        return new RoutingDecision(RoutingDecision.Target.ACTIVEMQ, brandName, toXml(brandName, message));
    }

    /** Minimal ActivemqRequest XML (the real JOLT/XML mapping is a later step). */
    private static String toXml(String brandName, String message) {
        return "<ActivemqRequest><brand>" + (brandName == null ? "" : brandName)
                + "</brand><payload>" + (message == null ? "" : message) + "</payload></ActivemqRequest>";
    }
}

package com.idfcfirstbank.integration.digitaledge.application;

import java.util.Map;

/**
 * A validated, partner-resolved origination request — the framework-free input to
 * {@link DigitalIngressService}. {@code partner} is resolved from the inbound
 * auth (config), never trusted from the body.
 */
public record DigitalOriginationCommand(
        String partner,
        String requestId,
        String applicationRef,
        String type,
        String orgId,
        String correlationId,
        Map<String, Object> payload) {

    /** The composite fallback key: stable across a resend of the same application. */
    public String applicationKey() {
        return partner + "::" + applicationRef;
    }
}

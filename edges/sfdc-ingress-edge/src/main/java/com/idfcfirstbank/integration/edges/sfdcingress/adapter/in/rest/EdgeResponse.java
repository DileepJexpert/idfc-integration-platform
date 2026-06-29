package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest;

/** Small response body echoing the edge verdict (fast-ACK semantics). */
public record EdgeResponse(String notificationId, String disposition, String reason, String correlationId) {
}

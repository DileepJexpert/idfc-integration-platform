package com.idfcfirstbank.integration.digitaledge.adapter.in.rest;

/** The synchronous fast-ACK to the partner: an applicationId + the edge verdict. */
public record DigitalAck(String applicationId, String status, String detail) {
}

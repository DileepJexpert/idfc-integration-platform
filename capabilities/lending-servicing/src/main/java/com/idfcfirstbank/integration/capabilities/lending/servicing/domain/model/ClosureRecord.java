package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model;

/** A loan-closure record this capability owns, keyed by LAN + event (dedup). */
public record ClosureRecord(String lan, String event, ClosureStatus status, String sfdcCaseId) {
}

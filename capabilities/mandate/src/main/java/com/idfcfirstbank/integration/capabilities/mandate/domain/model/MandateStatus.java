package com.idfcfirstbank.integration.capabilities.mandate.domain.model;

/** Mandate transaction lifecycle (BRD §3): pending -> success | failure. */
public enum MandateStatus { PENDING, SUCCESS, FAILURE }

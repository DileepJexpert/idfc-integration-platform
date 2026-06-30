package com.idfcfirstbank.integration.capabilities.lending.servicing.domain.model;

/** Loan-closure processing state (BRD §4). */
public enum ClosureStatus { MATURED, VALIDATION_FAILED, SFDC_CREATED, ERROR }

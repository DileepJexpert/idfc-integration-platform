package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model;

/**
 * How the capability authenticates to a target SFDC org. {@code NONE} for the dev
 * WireMock (which ignores auth); {@code BEARER} carries the org's configured token as
 * {@code Authorization: Bearer}. Real SFDC OAuth (session token / JWT bearer) slots in
 * behind the same enum + config, no caller change.
 */
public enum SfdcAuthType {
    NONE,
    BEARER
}

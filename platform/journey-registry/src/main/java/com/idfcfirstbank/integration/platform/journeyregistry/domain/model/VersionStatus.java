package com.idfcfirstbank.integration.platform.journeyregistry.domain.model;

/**
 * Maker-checker lifecycle of one journey version (charter §8):
 * {@code DRAFT -> PENDING_APPROVAL -> PUBLISHED | REJECTED}.
 * Published and rejected versions are immutable. Wire names are the camelCase
 * strings the DAG Designer's ApprovalStatus enum uses.
 */
public enum VersionStatus {
    DRAFT("draft"),
    PENDING_APPROVAL("pendingApproval"),
    PUBLISHED("published"),
    REJECTED("rejected");

    private final String wireName;

    VersionStatus(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Editable = still in the maker-checker pipeline (blocks a second draft). */
    public boolean isEditable() {
        return this == DRAFT || this == PENDING_APPROVAL;
    }
}

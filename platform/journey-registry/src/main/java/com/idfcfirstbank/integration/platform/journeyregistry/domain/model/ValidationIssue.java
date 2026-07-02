package com.idfcfirstbank.integration.platform.journeyregistry.domain.model;

/**
 * One validation finding, in the EXACT wire vocabulary the DAG Designer's
 * ValidationIssue model uses ({@code code}/{@code severity}/{@code message}/
 * {@code nodeId}; severity {@code error|warning}; codes named after the
 * designer's ValidationCode enum where the rules overlap). The designer renders
 * these directly — this shape is part of the §7 seam.
 */
public record ValidationIssue(
        String code,
        String severity,
        String message,
        String nodeId) {

    public static final String ERROR = "error";
    public static final String WARNING = "warning";

    public static ValidationIssue error(String code, String message, String nodeId) {
        return new ValidationIssue(code, ERROR, message, nodeId);
    }

    public static ValidationIssue warning(String code, String message, String nodeId) {
        return new ValidationIssue(code, WARNING, message, nodeId);
    }

    public boolean isError() {
        return ERROR.equals(severity);
    }
}

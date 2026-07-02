package com.idfcfirstbank.integration.platform.journeyregistry.domain.error;

import com.idfcfirstbank.integration.platform.journeyregistry.domain.model.ValidationIssue;

import java.util.List;

/**
 * The registry's typed failure — the REST adapter maps {@link Kind} to a status
 * code, so business rules live in the service and transport stays a mapping.
 * Messages carry ids only, never config/business content (PII discipline).
 */
public class RegistryException extends RuntimeException {

    public enum Kind {
        /** No such journey/version. -> 404 */
        NOT_FOUND,
        /** Lifecycle rule violated (already exists, wrong status, second draft, lost race). -> 409 */
        CONFLICT,
        /** Maker-checker violated: the author may not approve/reject their own version. -> 403 */
        FORBIDDEN,
        /** Mutating call without an actor identity. -> 401 */
        UNAUTHENTICATED,
        /** The §7 config failed graph validation; {@link #issues()} carries the details. -> 422 */
        VALIDATION_FAILED
    }

    private final Kind kind;
    private final transient List<ValidationIssue> issues;

    public RegistryException(Kind kind, String message) {
        this(kind, message, List.of());
    }

    public RegistryException(Kind kind, String message, List<ValidationIssue> issues) {
        super(message);
        this.kind = kind;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public Kind kind() {
        return kind;
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}

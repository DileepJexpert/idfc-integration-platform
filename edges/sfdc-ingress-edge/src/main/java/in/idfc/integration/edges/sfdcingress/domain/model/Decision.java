package in.idfc.integration.edges.sfdcingress.domain.model;

/**
 * The lending decision attached to a record at the DECIDED transition. The edge
 * treats this as an opaque sink value (NO business logic): it carries it to the
 * idempotency record and pushes it back to SFDC. {@code termsJson} is opaque.
 */
public record Decision(String outcome, String applicationId, String termsJson) {
    public Decision {
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("decision outcome is required");
        }
    }
}

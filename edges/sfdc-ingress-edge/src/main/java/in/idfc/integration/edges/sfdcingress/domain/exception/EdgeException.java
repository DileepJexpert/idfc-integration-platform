package in.idfc.integration.edges.sfdcingress.domain.exception;

/**
 * Base for edge domain errors. Each carries whether it is PROVABLY PERMANENT
 * (C2: ACK + DLQ) or POSSIBLY TRANSIENT (C2: do NOT ACK, let SFDC redeliver).
 * Unclassified failures default to transient (fail safe toward redelivery).
 */
public abstract class EdgeException extends RuntimeException {
    private final boolean permanent;

    protected EdgeException(String message, boolean permanent) {
        super(message);
        this.permanent = permanent;
    }

    protected EdgeException(String message, Throwable cause, boolean permanent) {
        super(message, cause);
        this.permanent = permanent;
    }

    public boolean isPermanent() {
        return permanent;
    }
}

package in.idfc.integration.edges.sfdcingress.domain.port;

import in.idfc.integration.edges.sfdcingress.domain.model.IdempotencyRecord;

/**
 * Outcome of a compare-and-set status transition (C4). {@code applied=true}
 * carries the updated record (with a bumped version); {@code applied=false}
 * means the generation moved under us (someone transitioned first) and
 * {@code record} carries the current persisted state for re-evaluation.
 */
public record CasResult(boolean applied, IdempotencyRecord record) {
    public static CasResult applied(IdempotencyRecord updated) {
        return new CasResult(true, updated);
    }

    public static CasResult stale(IdempotencyRecord current) {
        return new CasResult(false, current);
    }
}

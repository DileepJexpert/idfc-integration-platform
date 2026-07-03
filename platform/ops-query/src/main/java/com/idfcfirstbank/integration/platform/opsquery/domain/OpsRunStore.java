package com.idfcfirstbank.integration.platform.opsquery.domain;

import java.util.List;
import java.util.Optional;

/**
 * IN port from the ops module's perspective: the HOST app (the engine) adapts
 * its run store to this read-only view. The module never sees engine internals
 * and — by construction — has nothing it could mutate.
 */
public interface OpsRunStore {

    /** Fast-path single-run read. */
    Optional<OpsRun> find(String runId);

    /**
     * Every visible run (terminal runs age out via the host store's TTL, which
     * bounds this). Filtering/pagination happen SERVER-SIDE in the ops service —
     * a filtered secondary index is the documented prod optimization (D15).
     */
    List<OpsRun> scanAll();
}

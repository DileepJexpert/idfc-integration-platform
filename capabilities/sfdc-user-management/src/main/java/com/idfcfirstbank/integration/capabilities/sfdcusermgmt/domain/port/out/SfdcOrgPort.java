package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;

import java.util.Map;

/**
 * OUT port: make one real HTTP call to a resolved SFDC org target and return its
 * response body. A 2xx body (data, or a business "no" shape) is returned as-is; any
 * transport failure throws {@link com.idfcfirstbank.integration.shared.sync.SyncTechnicalException}
 * with an {@link com.idfcfirstbank.integration.shared.domain.capability.ErrorClass} —
 * the read-vs-action safety line is applied here (a read timeout is TRANSIENT/safe-to-
 * retry; a write timeout is AMBIGUOUS and only safe to retry under the idempotency key).
 *
 * <p>The dev/test adapter talks to the mock-sfdc-org WireMocks; the production adapter
 * is the same class pointed (by config) at real SFDC orgs.
 */
public interface SfdcOrgPort {

    Map<String, Object> call(ResolvedSfdcTarget target, Map<String, Object> requestBody);
}

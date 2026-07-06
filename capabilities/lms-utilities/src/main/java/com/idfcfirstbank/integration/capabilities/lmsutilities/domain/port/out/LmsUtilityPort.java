package com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out;

import java.util.Map;

/**
 * OUT port to the LMS-utilities backend. The real adapter does actual HTTP with
 * mandatory connect/read timeouts (the caller is waiting — a hung downstream must
 * fail fast, not hang the HTTP thread). A 200 house-envelope body — whether it
 * carries offer rows or an empty {@code resource_data} (a business "no offer") — is
 * returned RAW and UNINSPECTED here; the service normalizes it via the shared
 * HouseEnvelopeMapper. A transport failure (timeout, 5xx, unreachable) throws
 * {@link com.idfcfirstbank.integration.shared.sync.SyncTechnicalException}.
 */
public interface LmsUtilityPort {

    Map<String, Object> call(Map<String, Object> requestBody);
}

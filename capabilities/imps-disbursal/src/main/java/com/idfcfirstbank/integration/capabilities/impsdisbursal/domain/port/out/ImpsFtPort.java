package com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtRequest;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;

/**
 * OUT port to the IMPS/FT backend. The real adapter does actual HTTP with
 * mandatory connect/read timeouts (the caller is waiting — a hung downstream must
 * fail fast, not hang the HTTP thread). A 200 body — success OR a business decline
 * — is returned as an {@link ImpsFtResult}; a transport failure (timeout, 5xx,
 * unreachable) throws
 * {@link com.idfcfirstbank.integration.shared.sync.SyncTechnicalException}.
 */
public interface ImpsFtPort {

    ImpsFtResult transfer(ImpsFtRequest request);
}

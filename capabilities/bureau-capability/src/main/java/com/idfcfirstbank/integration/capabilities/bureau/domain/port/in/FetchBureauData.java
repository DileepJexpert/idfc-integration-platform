package com.idfcfirstbank.integration.capabilities.bureau.domain.port.in;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauFetchResponse;

/**
 * The capability's single use-case (IN port): fetch normalized bureau data for
 * an applicant. Invoked via REST in Slice 2 (later via orchestration). The
 * implementation fans out to the requested bureaus, normalizes each to canonical,
 * and merges — it does NOT score or decide.
 */
public interface FetchBureauData {
    BureauFetchResponse fetch(BureauFetchRequest request);
}

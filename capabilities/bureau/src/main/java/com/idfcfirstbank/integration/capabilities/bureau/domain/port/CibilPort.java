package com.idfcfirstbank.integration.capabilities.bureau.domain.port;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReport;

import java.util.Map;

/**
 * OUT port to CIBIL (credit bureau vendor). The real adapter is HTTP (URL via
 * config); the mock adapter fetches locally. The domain never knows which is
 * wired.
 */
public interface CibilPort {
    /** Fetch a credit bureau report + score from applicant identity (pan, name, ...). */
    BureauReport fetch(Map<String, Object> identity);
}

package com.idfcfirstbank.integration.capabilities.customer.party.domain.port;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;

import java.util.Map;

/**
 * OUT port to Posidex (customer source of truth). The real adapter is HTTP
 * (URL via config); the mock adapter resolves locally. The domain never knows
 * which is wired.
 */
public interface PosidexPort {
    /** Resolve/dedup a customer from applicant identity (name, dob, pan, ...). */
    CustomerProfile resolve(Map<String, Object> identity);
}

package com.idfcfirstbank.integration.capabilities.kyc.domain.port;

import com.idfcfirstbank.integration.capabilities.kyc.domain.model.KycResult;

import java.util.Map;

/**
 * OUT port to NSDL (KYC vendor). The real adapter is HTTP (URL via config); the
 * mock adapter verifies locally. The domain never knows which is wired.
 */
public interface NsdlPort {
    /** Verify KYC for the applicant from identity (name, dob, pan, ...). */
    KycResult verify(Map<String, Object> identity);
}

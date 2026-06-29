package com.idfcfirstbank.integration.capabilities.kyc.adapter.out.nsdl;

import com.idfcfirstbank.integration.capabilities.kyc.domain.model.KycResult;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;

import java.util.Map;

/**
 * Local mock NSDL — deterministic KYC outcome derived from the applicant's PAN.
 * Used for unit tests and when {@code idfc.kyc.nsdl.mode=mock} (no docker vendor
 * needed).
 */
public class MockNsdlAdapter implements NsdlPort {

    @Override
    public KycResult verify(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        return new KycResult("VERIFIED", "KYC-" + pan);
    }
}

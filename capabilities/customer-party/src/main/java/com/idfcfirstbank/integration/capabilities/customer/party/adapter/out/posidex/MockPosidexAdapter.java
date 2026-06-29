package com.idfcfirstbank.integration.capabilities.customer.party.adapter.out.posidex;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;

import java.util.Map;

/**
 * Local mock Posidex — deterministic profile derived from the applicant's PAN.
 * Used for unit tests and when {@code idfc.customer-party.posidex.mode=mock}
 * (no docker vendor needed).
 */
public class MockPosidexAdapter implements PosidexPort {

    @Override
    public CustomerProfile resolve(Map<String, Object> identity) {
        String pan = String.valueOf(identity.getOrDefault("pan", "UNKNOWN"));
        String name = String.valueOf(identity.getOrDefault("name", "Demo Customer"));
        return new CustomerProfile("CRN-" + pan, "CUST-" + pan, name, "ACTIVE");
    }
}

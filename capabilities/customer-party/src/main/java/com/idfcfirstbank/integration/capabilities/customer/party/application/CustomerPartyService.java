package com.idfcfirstbank.integration.capabilities.customer.party.application;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free handler: resolve the customer via {@link PosidexPort} and map
 * the profile into a contract {@link CapabilityResponse}. On any failure it
 * returns {@link CapabilityStatus#ERROR} (the engine fails the journey).
 */
public class CustomerPartyService {

    private static final Logger log = LoggerFactory.getLogger(CustomerPartyService.class);

    private final PosidexPort posidex;

    public CustomerPartyService(PosidexPort posidex) {
        this.posidex = posidex;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            CustomerProfile profile = posidex.resolve(request.payload());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("crn", profile.crn());
            result.put("customerId", profile.customerId());
            result.put("customerName", profile.name());
            result.put("customerStatus", profile.status());
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("customer-party.resolve failed for instance {}", request.journeyInstanceId(), e);
            return error(request);
        }
    }

    private static CapabilityResponse ok(CapabilityRequest req, Map<String, Object> result) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.OK, result);
    }

    private static CapabilityResponse error(CapabilityRequest req) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.ERROR, Map.of());
    }
}

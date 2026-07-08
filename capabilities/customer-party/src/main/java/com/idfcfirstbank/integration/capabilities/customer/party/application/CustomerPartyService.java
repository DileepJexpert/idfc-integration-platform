package com.idfcfirstbank.integration.capabilities.customer.party.application;

import com.idfcfirstbank.integration.capabilities.customer.party.domain.model.CustomerProfile;
import com.idfcfirstbank.integration.capabilities.customer.party.domain.port.PosidexPort;
import com.idfcfirstbank.integration.shared.capability.CapabilityException;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free handler: resolve the customer via {@link PosidexPort} and map the
 * profile into a contract {@link CapabilityResponse}. On failure it returns an ERROR
 * response CARRYING the {@link ErrorClass} the port classified, so the engine's retry
 * policy sees the truth — a TRANSIENT posidex outage retries, only a PERMANENT (4xx /
 * empty body) goes straight to DLQ. An unclassified error is conservatively PERMANENT.
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
        } catch (CapabilityException e) {
            // Preserve the port's classification — do NOT collapse every failure to
            // PERMANENT (the old bug: a down posidex hard-failed to DLQ instead of retrying).
            log.error("customer-party.resolve failed [{}] for instance {}",
                    e.errorClass(), request.journeyInstanceId(), e);
            return CapabilityResponse.error(request, e.errorClass());
        } catch (RuntimeException e) {
            // Unclassified: conservatively PERMANENT (don't blind-retry an unknown failure).
            log.error("customer-party.resolve failed [unclassified->PERMANENT] for instance {}",
                    request.journeyInstanceId(), e);
            return CapabilityResponse.error(request, ErrorClass.PERMANENT);
        }
    }

    private static CapabilityResponse ok(CapabilityRequest req, Map<String, Object> result) {
        return new CapabilityResponse(req.journeyInstanceId(), req.correlationId(), req.nodeId(),
                req.capabilityKey(), CapabilityStatus.OK, result);
    }
}

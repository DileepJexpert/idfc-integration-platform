package com.idfcfirstbank.integration.capabilities.lending.origination.application;

import com.idfcfirstbank.integration.capabilities.lending.origination.domain.model.LoanBooking;
import com.idfcfirstbank.integration.capabilities.lending.origination.domain.port.FinnOneBookingPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free handler: for an APPROVED application, book the loan in FinnOne
 * via {@link FinnOneBookingPort} and map the booking into a contract
 * {@link CapabilityResponse}. On any failure it returns
 * {@link CapabilityStatus#ERROR} (the engine fails the journey).
 *
 * <p>The result key {@code loanId} is read by the engine for the decision —
 * it MUST be exactly {@code loanId}.
 */
public class LendingOriginationService {

    private static final Logger log = LoggerFactory.getLogger(LendingOriginationService.class);

    private final FinnOneBookingPort finnOne;

    public LendingOriginationService(FinnOneBookingPort finnOne) {
        this.finnOne = finnOne;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            Map<String, Object> application = buildApplication(request);
            LoanBooking booking = finnOne.book(application);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loanId", booking.loanId());
            result.put("status", booking.status());
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("lending-origination.book failed for instance {}", request.journeyInstanceId(), e);
            return error(request);
        }
    }

    /**
     * Assemble the FinnOne application map from the run payload plus upstream
     * collected results. Carries {@code applicationRef}/{@code crn} through when
     * present (payload wins, then collectedResults).
     */
    private static Map<String, Object> buildApplication(CapabilityRequest request) {
        Map<String, Object> application = new LinkedHashMap<>();
        Map<String, Object> collected = request.collectedResults();
        if (collected != null) {
            application.putAll(collected);
        }
        Map<String, Object> payload = request.payload();
        if (payload != null) {
            application.putAll(payload);
        }
        return application;
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

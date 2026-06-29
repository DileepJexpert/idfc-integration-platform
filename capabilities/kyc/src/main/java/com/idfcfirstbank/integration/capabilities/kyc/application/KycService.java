package com.idfcfirstbank.integration.capabilities.kyc.application;

import com.idfcfirstbank.integration.capabilities.kyc.domain.model.KycResult;
import com.idfcfirstbank.integration.capabilities.kyc.domain.port.NsdlPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free handler: verify KYC via {@link NsdlPort} and map the result
 * into a contract {@link CapabilityResponse}. On any failure it returns
 * {@link CapabilityStatus#ERROR} (the engine fails the journey).
 */
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final NsdlPort nsdl;

    public KycService(NsdlPort nsdl) {
        this.nsdl = nsdl;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            KycResult kyc = nsdl.verify(request.payload());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kycStatus", kyc.status());
            result.put("kycRefId", kyc.kycRefId());
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("kyc.verify failed for instance {}", request.journeyInstanceId(), e);
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

package com.idfcfirstbank.integration.capabilities.bureau.application;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReport;
import com.idfcfirstbank.integration.capabilities.bureau.domain.port.CibilPort;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Framework-free handler: fetch the credit bureau report via {@link CibilPort}
 * and map it into a contract {@link CapabilityResponse}. Bureau ONLY fetches —
 * it does not make a credit decision (that is scoring). On any failure it returns
 * {@link CapabilityStatus#ERROR} (the engine fails the journey).
 */
public class BureauService {

    private static final Logger log = LoggerFactory.getLogger(BureauService.class);

    private final CibilPort cibil;

    public BureauService(CibilPort cibil) {
        this.cibil = cibil;
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            BureauReport report = cibil.fetch(request.payload());
            Map<String, Object> result = new LinkedHashMap<>();
            // result key MUST be exactly "bureauScore" (scoring + engine read it by name).
            result.put("bureauScore", report.score());
            result.put("bureauGrade", report.grade());
            result.put("reportId", report.reportId());
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("bureau.fetch failed for instance {}", request.journeyInstanceId(), e);
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

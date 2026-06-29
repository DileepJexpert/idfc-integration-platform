package com.idfcfirstbank.integration.capabilities.bureau.application;

import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauReportSet;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauRequest;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.BureauType;
import com.idfcfirstbank.integration.capabilities.bureau.domain.model.CanonicalBureauResult;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityRequest;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityResponse;
import com.idfcfirstbank.integration.shared.domain.capability.CapabilityStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-free handler: build a canonical {@link BureauRequest} from the engine
 * payload, fan out across the requested bureaus via {@link BureauFetchService},
 * and map the merged set into a contract {@link CapabilityResponse}. Bureau ONLY
 * fetches — it does not decide (that is scoring).
 *
 * <p>The result carries the full {@code bureauResults[]} (one per bureau) AND a
 * single primary {@code bureauScore}/{@code bureauGrade} (the CIBIL score, or the
 * most conservative across bureaus) so downstream scoring and the engine's branch
 * read one number by name — unchanged by the fan-out.
 */
public class BureauService {

    private static final Logger log = LoggerFactory.getLogger(BureauService.class);

    private final BureauFetchService fetchService;
    private final List<BureauType> defaultBureauTypes;

    public BureauService(BureauFetchService fetchService, List<BureauType> defaultBureauTypes) {
        this.fetchService = fetchService;
        this.defaultBureauTypes = defaultBureauTypes == null || defaultBureauTypes.isEmpty()
                ? List.of(BureauType.CIBIL) : List.copyOf(defaultBureauTypes);
    }

    public CapabilityResponse handle(CapabilityRequest request) {
        try {
            BureauReportSet set = fetchService.fetch(toBureauRequest(request.payload()));

            List<Map<String, Object>> bureauResults = new ArrayList<>();
            for (CanonicalBureauResult r : set.results()) {
                bureauResults.add(r.toMap());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("bureauResults", bureauResults);
            // Primary score: scoring + the engine's branch read these by name.
            result.put("bureauScore", set.primaryScore());
            result.put("bureauGrade", set.primaryGrade());
            result.put("reportId", set.primaryReportId());
            return ok(request, result);
        } catch (RuntimeException e) {
            log.error("bureau.fetch failed for instance {}", request.journeyInstanceId(), e);
            return error(request);
        }
    }

    private BureauRequest toBureauRequest(Map<String, Object> payload) {
        return new BureauRequest(payload, bureauTypes(payload), str(payload.get("purpose"), "ORIGINATION"),
                str(payload.get("consentRef"), null));
    }

    private List<BureauType> bureauTypes(Map<String, Object> payload) {
        Object requested = payload.get("bureauTypes");
        if (requested instanceof List<?> list && !list.isEmpty()) {
            List<BureauType> types = new ArrayList<>();
            for (Object o : list) {
                try {
                    types.add(BureauType.valueOf(String.valueOf(o).toUpperCase()));
                } catch (IllegalArgumentException ignored) {
                    // skip an unknown bureau type rather than failing the pull
                }
            }
            if (!types.isEmpty()) {
                return types;
            }
        }
        return defaultBureauTypes;
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : String.valueOf(v);
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

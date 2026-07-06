package com.idfcfirstbank.integration.capabilities.lmsutilities.application;

import com.idfcfirstbank.integration.capabilities.lmsutilities.config.LmsUtilitiesProperties;
import com.idfcfirstbank.integration.capabilities.lmsutilities.domain.model.LmsRequest;
import com.idfcfirstbank.integration.capabilities.lmsutilities.domain.port.out.LmsUtilityPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.HouseEnvelopeMapper;
import com.idfcfirstbank.integration.shared.sync.HouseEnvelopeMapper.HouseEnvelope;
import com.idfcfirstbank.integration.shared.sync.SyncInvocable;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The lms-utilities SYNC use case. It is the {@link SyncInvocable} the sync invoker
 * dispatches "lms-utilities" to. Flow, in-thread:
 *
 * <ol>
 *   <li><b>Fail-closed dispatch</b> — the {@code operation} IS the LMS requestCode
 *       (OFFER_CHECK, …). A blank code, or one NOT in the configured
 *       {@code known-request-codes} allow-list, throws a PERMANENT
 *       {@link SyncTechnicalException} BEFORE any backend call. Adding a requestCode
 *       is CONFIG; an unknown one never silently runs.</li>
 *   <li><b>Real query</b> via {@link LmsUtilityPort} (real HTTP, timeouts), returning
 *       the RAW house envelope.</li>
 *   <li><b>Normalize</b> the house envelope via the shared {@link HouseEnvelopeMapper}
 *       and return its clean response body. A SUCCESS with an EMPTY
 *       {@code resource_data} is a legitimate business "no offer" — a clean empty
 *       result, NOT an error. A transport failure has already thrown
 *       {@link SyncTechnicalException}, never a fake success.</li>
 * </ol>
 *
 * <p>No PII is logged — only ids (correlationId, requestCode).
 */
@Service
public class LmsUtilitiesService implements SyncInvocable {

    private static final Logger log = LoggerFactory.getLogger(LmsUtilitiesService.class);

    public static final String KEY = "lms-utilities";

    private final LmsUtilityPort lms;
    private final LmsUtilitiesProperties props;
    private final HouseEnvelopeMapper houseMapper = new HouseEnvelopeMapper();

    public LmsUtilitiesService(LmsUtilityPort lms, LmsUtilitiesProperties props) {
        this.lms = lms;
        this.props = props;
    }

    @Override
    public String capabilityKey() {
        return KEY;
    }

    @Override
    public Map<String, Object> invoke(String operation, Map<String, Object> payload, SyncRequestContext context) {
        // FAIL CLOSED: the requestCode must be a known, configured code — never dispatch an unknown one.
        if (!props.isKnown(operation)) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_REQUEST_CODE",
                    "lms-utilities has no known requestCode '" + operation + "'");
        }
        LmsRequest req = LmsRequest.fromPayload(payload);
        Map<String, Object> raw = lms.call(req.toVendorBody());   // throws SyncTechnicalException on transport failure
        HouseEnvelope env = houseMapper.map(raw);
        log.info("lms.query done requestCode={} status={} rows={} correlationId={}",
                operation, env.status(), env.hasData() ? env.resourceData().size() : 0,
                context == null ? null : context.correlationId());
        // An empty resource_data on a SUCCESS is a legitimate business "no offer" — returned clean, not an error.
        return env.toResponseBody();
    }
}

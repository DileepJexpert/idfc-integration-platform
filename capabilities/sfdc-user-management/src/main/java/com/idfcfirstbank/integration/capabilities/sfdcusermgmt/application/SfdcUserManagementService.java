package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperPair;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperRegistry;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcOrgPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncInvocable;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The sfdc-user-management SYNC use case — the {@link SyncInvocable} the sync invoker
 * dispatches "sfdc-user-management" to. Flow, in-thread:
 *
 * <ol>
 *   <li><b>Resolve</b> — svcName (the operation) selects the path; the request's
 *       {@code orgName} selects the target SFDC org. Unknown svcName / org fail closed
 *       (PERMANENT) before any call.</li>
 *   <li><b>Read vs write</b> — SLICE 1 serves READS only. A write svcName is refused
 *       ({@code WRITE_NOT_ENABLED}) until the idempotent write path lands (slice 2), so
 *       an identity mutation is never sent without its idempotency guarantee.</li>
 *   <li><b>Map + call</b> — the mapper pair (passthrough by default) shapes the request,
 *       the {@link SfdcOrgPort} makes the real call, the response is mapped back. A 2xx
 *       body is returned (a business "no" is a body, not an exception); a transport
 *       failure throws {@link SyncTechnicalException} and is NOT cached.</li>
 * </ol>
 *
 * <p>Request envelope: {@code { "svcName": ..., "orgName": ..., "payload": { ... } }} —
 * {@code svcName}/{@code orgName} are control fields (they select route + target and are
 * NOT forwarded downstream); {@code payload} is the operation body. If {@code payload} is
 * absent the body minus the control fields is used. No PII is logged — ids only.
 */
@Service
public class SfdcUserManagementService implements SyncInvocable {

    private static final Logger log = LoggerFactory.getLogger(SfdcUserManagementService.class);

    public static final String KEY = "sfdc-user-management";
    static final String ORG_NAME = "orgName";
    static final String SVC_NAME = "svcName";
    static final String PAYLOAD = "payload";

    private final SfdcOrgRouteResolver resolver;
    private final SfdcMapperRegistry mappers;
    private final SfdcOrgPort sfdcOrg;

    public SfdcUserManagementService(SfdcOrgRouteResolver resolver, SfdcMapperRegistry mappers, SfdcOrgPort sfdcOrg) {
        this.resolver = resolver;
        this.mappers = mappers;
        this.sfdcOrg = sfdcOrg;
    }

    @Override
    public String capabilityKey() {
        return KEY;
    }

    @Override
    public Map<String, Object> invoke(String operation, Map<String, Object> payload, SyncRequestContext context) {
        String svcName = operation;               // the svcName IS the operation (like lms requestCode)
        String orgName = str(payload, ORG_NAME);

        ResolvedSfdcTarget target = resolver.resolve(svcName, orgName);   // fail-closed on unknown svcName/org

        if (target.write()) {
            // Slice-1 boundary: a state change must go through the idempotent write path
            // (slice 2). Refuse rather than mutate identity data without the guarantee.
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "WRITE_NOT_ENABLED",
                    "write svcName '" + svcName + "' requires the idempotent write path (not in slice 1)");
        }

        SfdcMapperPair pair = mappers.forSvcName(svcName);
        Map<String, Object> downstreamReq = pair.request().apply(operationPayload(payload));
        log.info("sfdc-user-mgmt.read svcName={} orgName={} correlationId={}",
                svcName, orgName, context == null ? null : context.correlationId());

        Map<String, Object> raw = sfdcOrg.call(target, downstreamReq);   // throws SyncTechnicalException on transport failure
        return pair.response().apply(raw);
    }

    /**
     * The operation body to send downstream: the nested {@code payload} map if present,
     * otherwise the request body with the control fields ({@code svcName}/{@code orgName})
     * stripped — either way the routing keys never reach SFDC.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> operationPayload(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Object nested = body.get(PAYLOAD);
        if (nested instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        Map<String, Object> copy = new HashMap<>(body);
        copy.remove(SVC_NAME);
        copy.remove(ORG_NAME);
        return copy;
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

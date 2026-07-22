package com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application;

import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperPair;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.application.mapper.SfdcMapperRegistry;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.model.ResolvedSfdcTarget;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcIdempotencyStorePort;
import com.idfcfirstbank.integration.capabilities.sfdcusermgmt.domain.port.out.SfdcOrgPort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import com.idfcfirstbank.integration.shared.sync.SyncInvocable;
import com.idfcfirstbank.integration.shared.sync.SyncOutcome;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The sfdc-user-management SYNC use case — the {@link SyncInvocable} the sync invoker
 * dispatches "sfdc-user-management" to. svcName (the operation) selects the path; the
 * request's {@code orgName} selects the target SFDC org. Unknown svcName / org fail
 * closed (PERMANENT) before any call.
 *
 * <h2>Reads vs writes</h2>
 * <ul>
 *   <li><b>Read</b> (idempotent query): call the org, return the body. No idempotency
 *       key, no cache — a read is safe to repeat.</li>
 *   <li><b>Write</b> (state change): <b>idempotency FIRST</b> — a repeat of the same
 *       caller-supplied {@code idempotencyKey} (scoped by svcName+org) returns the PRIOR
 *       result and never re-calls SFDC (no double create/assign). A write WITHOUT a key
 *       is refused ({@code MISSING_IDEMPOTENCY_KEY}). Same-key writes are serialised on a
 *       striped lock so a concurrent duplicate can't slip past the check. Only DEFINITIVE
 *       outcomes are cached (a 2xx success OR a business rejection); a TECHNICAL failure
 *       throws BEFORE the cache write, so an ambiguous write stays retryable — mirrors
 *       imps-disbursal exactly.</li>
 * </ul>
 *
 * <h2>Business vs technical</h2>
 * An SFDC write returns a 2xx body carrying {@code success}: {@code success:false} (e.g.
 * a duplicate username) is a clean BUSINESS "no" — returned as a body, recorded as
 * BUSINESS_FAILURE, and cached as definitive. A 5xx / timeout / connect failure is a
 * TECHNICAL error — thrown as {@link SyncTechnicalException} with a class, never
 * mislabelled as a business outcome and never cached.
 *
 * <p>Request envelope: {@code { svcName, orgName, idempotencyKey?, payload:{...} }} —
 * the first three are control fields (they select route/target/dedup and are NOT
 * forwarded downstream); {@code payload} is the operation body. No PII is logged — ids only.
 */
@Service
public class SfdcUserManagementService implements SyncInvocable {

    private static final Logger log = LoggerFactory.getLogger(SfdcUserManagementService.class);

    public static final String KEY = "sfdc-user-management";
    static final String ORG_NAME = "orgName";
    static final String SVC_NAME = "svcName";
    static final String IDEMPOTENCY_KEY = "idempotencyKey";
    static final String PAYLOAD = "payload";

    private final SfdcOrgRouteResolver resolver;
    private final SfdcMapperRegistry mappers;
    private final SfdcOrgPort sfdcOrg;
    private final SfdcIdempotencyStorePort idempotency;
    /** Fixed lock stripes: serialise same-key writes without an unbounded per-key map. */
    private final Object[] stripes = new Object[64];

    public SfdcUserManagementService(SfdcOrgRouteResolver resolver, SfdcMapperRegistry mappers,
                                     SfdcOrgPort sfdcOrg, SfdcIdempotencyStorePort idempotency) {
        this.resolver = resolver;
        this.mappers = mappers;
        this.sfdcOrg = sfdcOrg;
        this.idempotency = idempotency;
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
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
        return target.write()
                ? write(target, svcName, payload, context)
                : read(target, svcName, payload, context);
    }

    /** Idempotent query — no key, no cache; safe to repeat. */
    private Map<String, Object> read(ResolvedSfdcTarget target, String svcName,
                                     Map<String, Object> body, SyncRequestContext ctx) {
        SfdcMapperPair pair = mappers.forSvcName(svcName);
        Map<String, Object> req = pair.request().apply(operationPayload(body));
        log.info("sfdc-user-mgmt.read svcName={} orgName={} correlationId={}",
                svcName, target.orgName(), corr(ctx));
        return pair.response().apply(sfdcOrg.call(target, req));
    }

    /**
     * State change — idempotency FIRST. A repeat of the same key returns the prior
     * definitive result and never re-calls SFDC. Only a 2xx (success or business "no")
     * is cached; a technical failure throws before the cache write and stays retryable.
     */
    private Map<String, Object> write(ResolvedSfdcTarget target, String svcName,
                                      Map<String, Object> body, SyncRequestContext ctx) {
        String callerKey = str(body, IDEMPOTENCY_KEY);
        if (callerKey == null || callerKey.isBlank()) {
            // A mutation without its dedup guarantee is refused — never mutate identity blind.
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "MISSING_IDEMPOTENCY_KEY",
                    "write '" + svcName + "' requires a caller-supplied idempotencyKey");
        }
        // Scope the key to svcName+org so a key means "this write, in this org".
        String storeKey = svcName + "::" + target.orgName() + "::" + callerKey;
        synchronized (stripeFor(storeKey)) {
            Optional<Map<String, Object>> prior = idempotency.find(storeKey);
            if (prior.isPresent()) {
                log.info("sfdc-user-mgmt.write idempotent replay svcName={} orgName={} correlationId={}",
                        svcName, target.orgName(), corr(ctx));
                return prior.get();
            }
            SfdcMapperPair pair = mappers.forSvcName(svcName);
            Map<String, Object> req = pair.request().apply(operationPayload(body));
            // throws SyncTechnicalException on transport failure -> NOT cached -> retryable
            Map<String, Object> resp = pair.response().apply(sfdcOrg.call(target, req));
            idempotency.save(storeKey, resp);     // definitive: 2xx success OR business rejection
            log.info("sfdc-user-mgmt.write done svcName={} orgName={} success={} correlationId={}",
                    svcName, target.orgName(), resp.get("success"), corr(ctx));
            return resp;
        }
    }

    // --- Audit hooks (SyncInvocation) — ids-only, PII-safe ---------------------------

    /** The caller-supplied write dedup id (reads have none). */
    @Override
    public String idempotencyKeyOf(Map<String, Object> payload) {
        return str(payload, IDEMPOTENCY_KEY);
    }

    /** A 2xx {@code success:false} SFDC body is a BUSINESS "no"; anything else is a success. */
    @Override
    public SyncOutcome businessOutcome(Map<String, Object> response) {
        Object success = response == null ? null : response.get("success");
        boolean businessNo = Boolean.FALSE.equals(success) || "false".equalsIgnoreCase(String.valueOf(success));
        return businessNo ? SyncOutcome.BUSINESS_FAILURE : SyncOutcome.SUCCESS;
    }

    /** The created/affected SFDC record id, for the audit trail. */
    @Override
    public String downstreamRefOf(Map<String, Object> response) {
        Object id = response == null ? null : response.get("id");
        return id == null ? null : String.valueOf(id);
    }

    // --- helpers ---------------------------------------------------------------------

    /**
     * The operation body to send downstream: the nested {@code payload} map if present,
     * otherwise the request body with the control fields ({@code svcName}/{@code orgName}/
     * {@code idempotencyKey}) stripped — either way the routing/dedup keys never reach SFDC.
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
        copy.remove(IDEMPOTENCY_KEY);
        return copy;
    }

    private Object stripeFor(String key) {
        return stripes[(key.hashCode() & 0x7fffffff) % stripes.length];
    }

    private static String corr(SyncRequestContext ctx) {
        return ctx == null ? null : ctx.correlationId();
    }

    private static String str(Map<String, Object> payload, String key) {
        Object v = payload == null ? null : payload.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

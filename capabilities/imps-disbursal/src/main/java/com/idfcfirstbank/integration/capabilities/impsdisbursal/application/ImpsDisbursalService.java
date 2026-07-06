package com.idfcfirstbank.integration.capabilities.impsdisbursal.application;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.error.SyncTechnicalException;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtRequest;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.ImpsFtResult;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.SyncRequestContext;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsFtPort;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.port.out.ImpsIdempotencyStorePort;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * The imps-disbursal SYNC use case. It is the {@link SyncInvocable} the sync
 * invoker dispatches "imps-disbursal" to. Flow, in-thread:
 *
 * <ol>
 *   <li><b>Idempotency FIRST</b> — a repeat of the same {@code idempotentId}
 *       returns the PRIOR result and never re-calls the backend (no double
 *       transfer). Same-key requests are serialized on a striped lock so a
 *       concurrent duplicate can't slip past the check (within this JVM; the
 *       production Aerospike store reserves the key cross-JVM).</li>
 *   <li><b>Real transfer</b> via {@link ImpsFtPort} (real HTTP, timeouts).</li>
 *   <li><b>Classify</b> — {@code status:S} is a success; any other status (with
 *       {@code errCode}/{@code errMessage}) is a BUSINESS "no", returned as-is.
 *       Both are DEFINITIVE and get cached under the key. A transport failure
 *       throws {@link SyncTechnicalException} BEFORE the cache write, so it is not
 *       stored — an ambiguous transfer stays retryable.</li>
 * </ol>
 *
 * <p>No PII is logged — only ids (reqId/idempotentId/correlationId).
 */
@Service
public class ImpsDisbursalService implements SyncInvocable {

    private static final Logger log = LoggerFactory.getLogger(ImpsDisbursalService.class);

    public static final String KEY = "imps-disbursal";
    public static final String OP_TRANSFER = "transfer";

    private final ImpsFtPort impsFt;
    private final ImpsIdempotencyStorePort idempotency;
    /** Fixed lock stripes: serialize same-key transfers without an unbounded per-key map. */
    private final Object[] stripes = new Object[64];

    public ImpsDisbursalService(ImpsFtPort impsFt, ImpsIdempotencyStorePort idempotency) {
        this.impsFt = impsFt;
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
        if (!OP_TRANSFER.equals(operation)) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "UNKNOWN_OPERATION",
                    "imps-disbursal has no operation '" + operation + "'");
        }
        return transfer(ImpsFtRequest.fromPayload(payload), context).toResponseBody();
    }

    /**
     * The idempotent transfer. The controller guarantees {@code idempotentId} is
     * present (a money movement without one is a 400 there); this method treats a
     * missing key defensively as a PERMANENT technical error rather than moving
     * money unprotected.
     */
    public ImpsFtResult transfer(ImpsFtRequest request, SyncRequestContext context) {
        if (!request.hasIdempotencyKey()) {
            throw new SyncTechnicalException(ErrorClass.PERMANENT, "MISSING_IDEMPOTENCY_KEY",
                    "refusing an IMPS transfer with no idempotentId");
        }
        String key = request.idempotentId();
        synchronized (stripeFor(key)) {
            Optional<ImpsFtResult> prior = idempotency.find(key);
            if (prior.isPresent()) {
                log.info("imps.transfer idempotent replay idempotentId={} reqId={} correlationId={}",
                        key, request.reqId(), context == null ? null : context.correlationId());
                return prior.get();
            }
            ImpsFtResult result = impsFt.transfer(request);   // throws SyncTechnicalException on transport failure
            idempotency.save(key, result);                    // definitive outcome (success or business decline)
            log.info("imps.transfer done idempotentId={} reqId={} status={} correlationId={}",
                    key, request.reqId(), result.status(), context == null ? null : context.correlationId());
            return result;
        }
    }

    private Object stripeFor(String key) {
        return stripes[(key.hashCode() & 0x7fffffff) % stripes.length];
    }
}

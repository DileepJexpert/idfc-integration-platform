package com.idfcfirstbank.integration.capabilities.impsdisbursal.adapter.in.rest;

import com.idfcfirstbank.integration.capabilities.impsdisbursal.application.ImpsDisbursalService;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.application.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.error.SyncTechnicalException;
import com.idfcfirstbank.integration.capabilities.impsdisbursal.domain.model.SyncRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The SYNCHRONOUS IMPS fund-transfer door — the real INDMONEY {@code POST
 * /api/v1/impsFT} path. The caller BLOCKS for the result on this same call: NO
 * journey engine, NO Kafka, NO journeyInstanceId. Fail-closed Bearer auth →
 * structural check → in-thread invoke → the mapped IMPS result. A business "no"
 * (status ≠ S) comes back as a normal 200 envelope; a downstream technical failure
 * is a uniform 502 (never a fake success). {@code source} (INDMONEY) is recorded
 * for trace only — it does not fork the code path.
 */
@RestController
public class ImpsFtController {

    private static final Logger log = LoggerFactory.getLogger(ImpsFtController.class);

    private final SyncCapabilityInvoker invoker;
    private final BearerTokenValidator bearer;

    public ImpsFtController(SyncCapabilityInvoker invoker, BearerTokenValidator bearer) {
        this.invoker = invoker;
        this.bearer = bearer;
    }

    @PostMapping("/api/v1/impsFT")
    public ResponseEntity<?> impsFt(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "correlationId", required = false) String correlationId,
            @RequestHeader(value = "transactionId", required = false) String transactionId,
            @RequestHeader(value = "source", required = false) String source,
            @RequestBody Map<String, Object> body) {

        if (!bearer.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(SyncErrorResponse.of("UNAUTHENTICATED", "PERMANENT",
                            "missing or invalid Authorization: Bearer token"));
        }
        // Money movement demands an idempotency key — reject before we touch the backend.
        Object idempotentId = body == null ? null : body.get("idempotentId");
        if (idempotentId == null || String.valueOf(idempotentId).isBlank()) {
            return ResponseEntity.badRequest()
                    .body(SyncErrorResponse.of("INVALID_REQUEST", "PERMANENT",
                            "idempotentId is required for an IMPS transfer"));
        }

        SyncRequestContext context = SyncRequestContext.of(correlationId, transactionId, source);
        try {
            Map<String, Object> result = invoker.invoke(
                    ImpsDisbursalService.KEY, ImpsDisbursalService.OP_TRANSFER, body, context);
            // Success AND business decline both return 200 + the envelope; the caller
            // reads `status` (S = moved, else the errCode/errMessage "no").
            return ResponseEntity.ok(result);
        } catch (SyncTechnicalException e) {
            // Downstream technical failure — uniform 5xx + internal alert (ids only), never a fake success.
            log.error("imps.transfer TECHNICAL failure code={} class={} reqId={} correlationId={}",
                    e.code(), e.errorClass(), idempotentId, correlationId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SyncErrorResponse.of(e.code(), e.errorClass().name(),
                            "the transfer could not be completed downstream"));
        } catch (RuntimeException e) {
            log.error("imps.transfer UNEXPECTED failure reqId={} correlationId={}",
                    idempotentId, correlationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SyncErrorResponse.of("INTERNAL", "PERMANENT", "unexpected error"));
        }
    }
}

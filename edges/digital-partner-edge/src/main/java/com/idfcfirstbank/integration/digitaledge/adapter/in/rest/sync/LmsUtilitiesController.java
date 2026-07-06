package com.idfcfirstbank.integration.digitaledge.adapter.in.rest.sync;

import com.idfcfirstbank.integration.shared.sync.BearerTokenValidator;
import com.idfcfirstbank.integration.shared.sync.SyncCapabilityInvoker;
import com.idfcfirstbank.integration.shared.sync.SyncErrorResponse;
import com.idfcfirstbank.integration.shared.sync.SyncRequestContext;
import com.idfcfirstbank.integration.shared.sync.SyncTechnicalException;
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
 * The SYNCHRONOUS LMS-utilities door — the real SAVEIN {@code POST
 * /api/v1/callLmsUtilities} path, hosted on the digital edge. The caller BLOCKS for
 * the result; the edge invokes the lms-utilities capability IN-THREAD. The
 * {@code requestCode} (OFFER_CHECK now; siblings by config) selects the operation —
 * an UNKNOWN requestCode fails closed (422), it never silently runs. A SUCCESS with
 * empty {@code resourceData} is a legitimate "no offer" (200, clean empty), not an
 * error; a downstream technical failure is a uniform 502. {@code source} (SAVEIN)
 * is trace-only, never a routing key.
 */
@RestController
public class LmsUtilitiesController {

    private static final Logger log = LoggerFactory.getLogger(LmsUtilitiesController.class);

    private static final String CAPABILITY = "lms-utilities";
    private static final String UNKNOWN_REQUEST_CODE = "UNKNOWN_REQUEST_CODE";

    private final SyncCapabilityInvoker invoker;
    private final BearerTokenValidator bearer;

    public LmsUtilitiesController(SyncCapabilityInvoker invoker, BearerTokenValidator bearer) {
        this.invoker = invoker;
        this.bearer = bearer;
    }

    @PostMapping("/api/v1/callLmsUtilities")
    public ResponseEntity<?> callLmsUtilities(
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
        Object requestCode = body == null ? null : body.get("requestCode");
        if (requestCode == null || String.valueOf(requestCode).isBlank()) {
            return ResponseEntity.badRequest()
                    .body(SyncErrorResponse.of("INVALID_REQUEST", "PERMANENT",
                            "requestCode is required"));
        }

        SyncRequestContext context = SyncRequestContext.of(correlationId, transactionId, source);
        try {
            // The requestCode IS the operation — the capability fails closed on an unknown one.
            Map<String, Object> result = invoker.invoke(CAPABILITY, String.valueOf(requestCode), body, context);
            return ResponseEntity.ok(result);   // SUCCESS (incl. an empty "no offer") -> 200
        } catch (SyncTechnicalException e) {
            if (UNKNOWN_REQUEST_CODE.equals(e.code())) {
                // A client-side error (unsupported requestCode), not a downstream failure.
                return ResponseEntity.unprocessableEntity()
                        .body(SyncErrorResponse.of(e.code(), e.errorClass().name(),
                                "unsupported requestCode"));
            }
            log.error("lms.callUtilities TECHNICAL failure code={} class={} requestCode={} correlationId={}",
                    e.code(), e.errorClass(), requestCode, correlationId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SyncErrorResponse.of(e.code(), e.errorClass().name(),
                            "the LMS query could not be completed downstream"));
        } catch (RuntimeException e) {
            log.error("lms.callUtilities UNEXPECTED failure requestCode={} correlationId={}",
                    requestCode, correlationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SyncErrorResponse.of("INTERNAL", "PERMANENT", "unexpected error"));
        }
    }
}

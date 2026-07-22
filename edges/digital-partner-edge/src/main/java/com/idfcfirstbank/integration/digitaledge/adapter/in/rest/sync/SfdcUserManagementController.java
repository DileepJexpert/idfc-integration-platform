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
import java.util.Set;

/**
 * The SYNCHRONOUS SFDC user-management door, hosted on the digital edge (the JMI
 * migration APIs for SFDC user/role/profile ops). The caller BLOCKS; the edge invokes
 * the sfdc-user-management capability IN-THREAD. {@code svcName} selects the operation
 * and {@code orgName} selects WHICH SFDC org instance to call — an unknown svcName or an
 * unknown/disabled org fails closed (422, it never silently runs or picks a default org).
 * A downstream technical failure is a uniform 502. {@code source} is trace-only, never a
 * routing key.
 *
 * <p><b>Ingress contract pending JMI confirmation.</b> These operations are exposed here
 * (JSON + {@code Authorization: Bearer}, the same fail-closed auth as impsFT/callLmsUtilities)
 * on the assumption the consumers are internal apps like the sync lane's other callers. If
 * the real transport differs, only this controller changes — the capability is untouched.
 */
@RestController
public class SfdcUserManagementController {

    private static final Logger log = LoggerFactory.getLogger(SfdcUserManagementController.class);

    private static final String CAPABILITY = "sfdc-user-management";

    /** Client-side "your request can't be processed as-is" — a 422, not a downstream 502. */
    private static final Set<String> CLIENT_ERROR_CODES =
            Set.of("NO_ROUTE", "UNKNOWN_ORG", "ORG_DISABLED", "BAD_ROUTE_PATH", "MISSING_IDEMPOTENCY_KEY");

    private final SyncCapabilityInvoker invoker;
    private final BearerTokenValidator bearer;

    public SfdcUserManagementController(SyncCapabilityInvoker invoker, BearerTokenValidator bearer) {
        this.invoker = invoker;
        this.bearer = bearer;
    }

    @PostMapping("/api/v1/sfdcUserManagement")
    public ResponseEntity<?> handle(
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
        String svcName = str(body, "svcName");
        if (svcName == null || svcName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(SyncErrorResponse.of("INVALID_REQUEST", "PERMANENT", "svcName is required"));
        }
        String orgName = str(body, "orgName");
        if (orgName == null || orgName.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(SyncErrorResponse.of("INVALID_REQUEST", "PERMANENT",
                            "orgName is required (it selects the target SFDC org)"));
        }

        SyncRequestContext context = SyncRequestContext.of(correlationId, transactionId, source);
        try {
            // svcName IS the operation; the capability fails closed on an unknown svcName/org.
            Map<String, Object> result = invoker.invoke(CAPABILITY, svcName, body, context);
            return ResponseEntity.ok(result);
        } catch (SyncTechnicalException e) {
            if (CLIENT_ERROR_CODES.contains(e.code())) {
                return ResponseEntity.unprocessableEntity()
                        .body(SyncErrorResponse.of(e.code(), e.errorClass().name(),
                                "svcName/orgName not available"));
            }
            log.error("sfdc-user-mgmt TECHNICAL failure code={} class={} svcName={} orgName={} correlationId={}",
                    e.code(), e.errorClass(), svcName, orgName, correlationId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(SyncErrorResponse.of(e.code(), e.errorClass().name(),
                            "the SFDC user-management call could not be completed downstream"));
        } catch (RuntimeException e) {
            log.error("sfdc-user-mgmt UNEXPECTED failure svcName={} orgName={} correlationId={}",
                    svcName, orgName, correlationId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(SyncErrorResponse.of("INTERNAL", "PERMANENT", "unexpected error"));
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

package com.idfcfirstbank.integration.edges.sfdcingress.adapter.in.rest;

import com.idfcfirstbank.integration.edges.sfdcingress.application.DecisionService;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.model.Decision;
import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.AuthTokenPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Decision-callback endpoint. In Slice 1 the downstream journey is mocked, so this
 * stands in for the path that delivers a decision back to the edge. It exists to
 * demonstrate the C1 ownership guard end-to-end: the push-back fires EXACTLY on
 * the CAS transition INTO DECIDED, never on a resend/duplicate decision.
 *
 * <p>Authenticated with the SAME edge token as the SOAP inbound (Phase 5): a
 * decision flips customer-visible state and fires the SFDC push-back — an open
 * endpoint would let any caller decide loan applications.
 */
@RestController
@RequestMapping("/api/v1/sfdc")
public class SfdcDecisionController {

    private final DecisionService decisionService;
    private final AuthTokenPort authTokenPort;

    public SfdcDecisionController(DecisionService decisionService, AuthTokenPort authTokenPort) {
        this.decisionService = decisionService;
        this.authTokenPort = authTokenPort;
    }

    @PostMapping("/decisions")
    public ResponseEntity<DecisionResponse> decide(
            @RequestHeader(value = "X-Auth-Token", required = false) String authToken,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody DecisionRequest request) {

        if (!authTokenPort.authenticate(authToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new DecisionResponse(request.notificationId(), false, "invalid or missing token"));
        }

        boolean pushed = decisionService.applyDecision(request.notificationId(),
                new Decision(request.outcome(), request.applicationId(), request.terms()), correlationId);
        return ResponseEntity.ok(new DecisionResponse(request.notificationId(), pushed,
                pushed ? "transitioned to DECIDED and pushed to SFDC" : "already decided; no push (C1)"));
    }

    public record DecisionRequest(String notificationId, String outcome, String applicationId, String terms) {
    }

    public record DecisionResponse(String notificationId, boolean pushed, String detail) {
    }
}

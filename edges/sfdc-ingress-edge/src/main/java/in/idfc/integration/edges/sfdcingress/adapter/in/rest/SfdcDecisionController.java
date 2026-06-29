package in.idfc.integration.edges.sfdcingress.adapter.in.rest;

import in.idfc.integration.edges.sfdcingress.application.DecisionService;
import in.idfc.integration.edges.sfdcingress.domain.model.Decision;
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
 */
@RestController
@RequestMapping("/api/v1/sfdc")
public class SfdcDecisionController {

    private final DecisionService decisionService;

    public SfdcDecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @PostMapping("/decisions")
    public DecisionResponse decide(
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestBody DecisionRequest request) {
        boolean pushed = decisionService.applyDecision(request.notificationId(),
                new Decision(request.outcome(), request.applicationId(), request.terms()), correlationId);
        return new DecisionResponse(request.notificationId(), pushed,
                pushed ? "transitioned to DECIDED and pushed to SFDC" : "already decided; no push (C1)");
    }

    public record DecisionRequest(String notificationId, String outcome, String applicationId, String terms) {
    }

    public record DecisionResponse(String notificationId, boolean pushed, String detail) {
    }
}
